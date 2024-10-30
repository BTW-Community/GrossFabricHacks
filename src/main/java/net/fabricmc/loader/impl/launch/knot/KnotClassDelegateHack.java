package net.fabricmc.loader.impl.launch.knot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

import net.devtech.grossfabrichacks.State;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import net.fabricmc.loader.impl.util.ExceptionUtil;
import net.fabricmc.loader.impl.util.FileSystemUtil;
import net.fabricmc.loader.impl.util.LoaderUtil;
import net.fabricmc.loader.impl.util.ManifestUtil;
import net.fabricmc.loader.impl.util.UrlConversionException;
import net.fabricmc.loader.impl.util.UrlUtil;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

final class KnotClassDelegateHack<T extends ClassLoader & KnotClassDelegate.ClassLoaderAccess> implements KnotClassLoaderInterface {
    private static final boolean LOG_CLASS_LOAD = System.getProperty("fabric.debug.logClassLoad") != null;
    private static final boolean LOG_CLASS_LOAD_ERRORS;
    private static final boolean LOG_TRANSFORM_ERRORS;
    private static final boolean DISABLE_ISOLATION;
    private static final ClassLoader PLATFORM_CLASS_LOADER;
    private final Map<Path, KnotClassDelegate.Metadata> metadataCache = new ConcurrentHashMap();
    private final T classLoader;
    private final ClassLoader parentClassLoader;
    private final GameProvider provider;
    private final boolean isDevelopment;
    private final EnvType envType;
    private IMixinTransformer mixinTransformer;
    private boolean transformInitialized = false;
    private volatile Set<Path> codeSources = Collections.emptySet();
    private volatile Set<Path> validParentCodeSources = Collections.emptySet();
    private final Map<Path, String[]> allowedPrefixes = new ConcurrentHashMap();
    private final Set<String> parentSourcedClasses = Collections.newSetFromMap(new ConcurrentHashMap());

    KnotClassDelegateHack(boolean isDevelopment, EnvType envType, T classLoader, ClassLoader parentClassLoader, GameProvider provider) {
        this.isDevelopment = isDevelopment;
        this.envType = envType;
        this.classLoader = classLoader;
        this.parentClassLoader = parentClassLoader;
        this.provider = provider;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    public void initializeTransformers() {
        if (this.transformInitialized) {
            throw new IllegalStateException("Cannot initialize KnotClassDelegateHack twice!");
        } else {
            this.mixinTransformer = MixinServiceKnot.getTransformer();
            if (this.mixinTransformer == null) {
                try {
                    Constructor<IMixinTransformer> ctor = (Constructor<IMixinTransformer>) Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getConstructor();
                    ctor.setAccessible(true);
                    this.mixinTransformer = (IMixinTransformer)ctor.newInstance();
                } catch (ReflectiveOperationException var2) {
                    Log.debug(LogCategory.KNOT, "Can't create Mixin transformer through reflection (only applicable for 0.8-0.8.2): %s", var2);
                    throw new IllegalStateException("mixin transformer unavailable?");
                }
            }

            this.transformInitialized = true;
        }
    }

    private IMixinTransformer getMixinTransformer() {
        assert this.mixinTransformer != null;

        return this.mixinTransformer;
    }

    public void addCodeSource(Path path) {
        path = LoaderUtil.normalizeExistingPath(path);
        synchronized(this) {
            Set<Path> codeSources = this.codeSources;
            if (codeSources.contains(path)) {
                return;
            }

            Set<Path> newCodeSources = new HashSet(codeSources.size() + 1, 1.0F);
            newCodeSources.addAll(codeSources);
            newCodeSources.add(path);
            this.codeSources = newCodeSources;
        }

        try {
            ((ClassLoaderAccess)this.classLoader).addUrlFwd(UrlUtil.asUrl(path));
        } catch (MalformedURLException var6) {
            throw new RuntimeException(var6);
        }

        if (LOG_CLASS_LOAD_ERRORS) {
            Log.info(LogCategory.KNOT, "added code source %s", new Object[]{path});
        }

    }

    public void setAllowedPrefixes(Path codeSource, String... prefixes) {
        codeSource = LoaderUtil.normalizeExistingPath(codeSource);
        if (prefixes.length == 0) {
            this.allowedPrefixes.remove(codeSource);
        } else {
            this.allowedPrefixes.put(codeSource, prefixes);
        }

    }

    public void setValidParentClassPath(Collection<Path> paths) {
        Set<Path> validPaths = new HashSet(paths.size(), 1.0F);
        Iterator var3 = paths.iterator();

        while(var3.hasNext()) {
            Path path = (Path)var3.next();
            validPaths.add(LoaderUtil.normalizeExistingPath(path));
        }

        this.validParentCodeSources = validPaths;
    }

    public Manifest getManifest(Path codeSource) {
        return this.getMetadata(LoaderUtil.normalizeExistingPath(codeSource)).manifest;
    }

    public boolean isClassLoaded(String name) {
        synchronized(((ClassLoaderAccess)this.classLoader).getClassLoadingLockFwd(name)) {
            return ((ClassLoaderAccess)this.classLoader).findLoadedClassFwd(name) != null;
        }
    }

    public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        synchronized(((ClassLoaderAccess)this.classLoader).getClassLoadingLockFwd(name)) {
            Class<?> c = ((ClassLoaderAccess)this.classLoader).findLoadedClassFwd(name);
            if (c == null) {
                c = this.tryLoadClass(name, true);
                if (c == null) {
                    throw new ClassNotFoundException("can't find class " + name);
                }

                if (LOG_CLASS_LOAD) {
                    Log.info(LogCategory.KNOT, "loaded class %s into target", new Object[]{name});
                }
            }

            ((ClassLoaderAccess)this.classLoader).resolveClassFwd(c);
            return c;
        }
    }

    Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized(((ClassLoaderAccess)this.classLoader).getClassLoadingLockFwd(name)) {
            Class<?> c = ((ClassLoaderAccess)this.classLoader).findLoadedClassFwd(name);
            if (c == null) {
                if (name.startsWith("java.")) {
                    c = PLATFORM_CLASS_LOADER.loadClass(name);
                } else {
                    c = this.tryLoadClass(name, false);
                    if (c == null) {
                        String fileName = LoaderUtil.getClassFileName(name);
                        URL url = this.parentClassLoader.getResource(fileName);
                        if (url == null) {
                            try {
                                c = PLATFORM_CLASS_LOADER.loadClass(name);
                                if (LOG_CLASS_LOAD) {
                                    Log.info(LogCategory.KNOT, "loaded resources-less class %s from platform class loader");
                                }
                            } catch (ClassNotFoundException var9) {
                                if (LOG_CLASS_LOAD_ERRORS) {
                                    Log.warn(LogCategory.KNOT, "can't find class %s", new Object[]{name});
                                }

                                throw var9;
                            }
                        } else {
                            if (!this.isValidParentUrl(url, fileName)) {
                                String msg = String.format("can't load class %s at %s as it hasn't been exposed to the game (yet? The system property fabric.classPathGroups may not be set correctly in-dev)", name, getCodeSource(url, fileName));
                                if (LOG_CLASS_LOAD_ERRORS) {
                                    Log.warn(LogCategory.KNOT, msg);
                                }

                                throw new ClassNotFoundException(msg);
                            }

                            if (LOG_CLASS_LOAD) {
                                Log.info(LogCategory.KNOT, "loading class %s using the parent class loader", new Object[]{name});
                            }

                            c = this.parentClassLoader.loadClass(name);
                        }
                    } else if (LOG_CLASS_LOAD) {
                        Log.info(LogCategory.KNOT, "loaded class %s", new Object[]{name});
                    }
                }
            }

            if (resolve) {
                ((ClassLoaderAccess)this.classLoader).resolveClassFwd(c);
            }

            return c;
        }
    }

    private boolean isValidParentUrl(URL url, String fileName) {
        if (url == null) {
            return false;
        } else if (DISABLE_ISOLATION) {
            return true;
        } else if (!hasRegularCodeSource(url)) {
            return true;
        } else {
            Path codeSource = getCodeSource(url, fileName);
            Set<Path> validParentCodeSources = this.validParentCodeSources;
            if (validParentCodeSources == null) {
                return !this.codeSources.contains(codeSource);
            } else {
                return validParentCodeSources.contains(codeSource) || PLATFORM_CLASS_LOADER.getResource(fileName) != null;
            }
        }
    }

    Class<?> tryLoadClass(String name, boolean allowFromParent) throws ClassNotFoundException {
        if (name.startsWith("java.")) {
            return null;
        } else {
            if (!this.allowedPrefixes.isEmpty() && !DISABLE_ISOLATION) {
                String fileName = LoaderUtil.getClassFileName(name);
                URL url = this.classLoader.getResource(fileName);
                if (url != null && hasRegularCodeSource(url)) {
                    Path codeSource = getCodeSource(url, fileName);
                    String[] prefixes = (String[])this.allowedPrefixes.get(codeSource);
                    if (prefixes != null) {
                        assert prefixes.length > 0;

                        boolean found = false;
                        String[] var8 = prefixes;
                        int var9 = prefixes.length;

                        for(int var10 = 0; var10 < var9; ++var10) {
                            String prefix = var8[var10];
                            if (name.startsWith(prefix)) {
                                found = true;
                                break;
                            }
                        }

                        if (!found) {
                            String msg = "class " + name + " is currently restricted from being loaded";
                            if (LOG_CLASS_LOAD_ERRORS) {
                                Log.warn(LogCategory.KNOT, msg);
                            }

                            throw new ClassNotFoundException(msg);
                        }
                    }
                }
            }

            if (!allowFromParent && !this.parentSourcedClasses.isEmpty()) {
                int pos = name.length();

                while((pos = name.lastIndexOf(36, pos - 1)) > 0) {
                    if (this.parentSourcedClasses.contains(name.substring(0, pos))) {
                        allowFromParent = true;
                        break;
                    }
                }
            }

            byte[] input = this.getPostMixinClassByteArray(name, allowFromParent);
            if (input == null) {
                return null;
            } else {
                Class<?> existingClass = ((ClassLoaderAccess)this.classLoader).findLoadedClassFwd(name);
                if (existingClass != null) {
                    return existingClass;
                } else {
                    if (allowFromParent) {
                        this.parentSourcedClasses.add(name);
                    }

                    KnotClassDelegate.Metadata metadata = this.getMetadata(name);
                    int pkgDelimiterPos = name.lastIndexOf(46);
                    if (pkgDelimiterPos > 0) {
                        String pkgString = name.substring(0, pkgDelimiterPos);
                        if (((ClassLoaderAccess)this.classLoader).getPackageFwd(pkgString) == null) {
                            try {
                                ((ClassLoaderAccess)this.classLoader).definePackageFwd(pkgString, (String)null, (String)null, (String)null, (String)null, (String)null, (String)null, (URL)null);
                            } catch (IllegalArgumentException var12) {
                                if (((ClassLoaderAccess)this.classLoader).getPackageFwd(pkgString) == null) {
                                    throw var12;
                                }
                            }
                        }
                    }

                    return ((ClassLoaderAccess)this.classLoader).defineClassFwd(name, input, 0, input.length, metadata.codeSource);
                }
            }
        }
    }

    private KnotClassDelegate.Metadata getMetadata(String name) {
        String fileName = LoaderUtil.getClassFileName(name);
        URL url = this.classLoader.getResource(fileName);
        return url != null && hasRegularCodeSource(url) ? this.getMetadata(getCodeSource(url, fileName)) : KnotClassDelegate.Metadata.EMPTY;
    }

    private KnotClassDelegate.Metadata getMetadata(Path codeSource) {
        return this.metadataCache.computeIfAbsent(codeSource, (path) -> {
            Manifest manifest = null;
            CodeSource cs = null;
            Certificate[] certificates = null;

            try {
                if (Files.isDirectory(path, new LinkOption[0])) {
                    manifest = ManifestUtil.readManifest(path);
                } else {
                    URLConnection connection = (new URL("jar:" + path.toUri().toString() + "!/")).openConnection();
                    if (connection instanceof JarURLConnection) {
                        manifest = ((JarURLConnection)connection).getManifest();
                        certificates = ((JarURLConnection)connection).getCertificates();
                    }

                    if (manifest == null) {
                        FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(path, false);

                        try {
                            manifest = ManifestUtil.readManifest((Path)jarFs.get().getRootDirectories().iterator().next());
                        } catch (Throwable var10) {
                            if (jarFs != null) {
                                try {
                                    jarFs.close();
                                } catch (Throwable var9) {
                                    var10.addSuppressed(var9);
                                }
                            }

                            throw var10;
                        }

                        if (jarFs != null) {
                            jarFs.close();
                        }
                    }
                }
            } catch (FileSystemNotFoundException | IOException var11) {
                if (FabricLauncherBase.getLauncher().isDevelopment()) {
                    Log.warn(LogCategory.KNOT, "Failed to load manifest", var11);
                }
            }

            if (cs == null) {
                try {
                    cs = new CodeSource(UrlUtil.asUrl(path), certificates);
                } catch (MalformedURLException var8) {
                    throw new RuntimeException(var8);
                }
            }

            return new KnotClassDelegate.Metadata(manifest, cs);
        });
    }

    private byte[] getPostMixinClassByteArray(String name, boolean allowFromParent) {
        byte[] transformedClassArray = this.getPreMixinClassByteArray(name, allowFromParent);
        if (this.transformInitialized && canTransformClass(name)) {
            try {
                return this.getMixinTransformer().transformClassBytes(name, name, transformedClassArray);
            } catch (Throwable var6) {
                String msg = String.format("Mixin transformation of %s failed", name);
                if (LOG_TRANSFORM_ERRORS) {
                    Log.warn(LogCategory.KNOT, msg, var6);
                }

                throw new RuntimeException(msg, var6);
            }
        } else {
            return transformedClassArray;
        }
    }

    public byte[] getPreMixinClassBytes(String name) {
        return this.getPreMixinClassByteArray(name, true);
    }

    private byte[] getPreMixinClassByteArray(String name, boolean allowFromParent) {
        name = name.replace('/', '.');
        if (this.transformInitialized && canTransformClass(name)) {
            byte[] input = this.provider.getEntrypointTransformer().transform(name);
            if (input == null || !name.equals(this.provider.getEntrypoint())) {
                try {
                    input = this.getRawClassByteArray(name, allowFromParent);
                } catch (IOException var5) {
                    throw new RuntimeException("Failed to load class file for '" + name + "'!", var5);
                }
            }

            return input != null ? FabricTransformer.transform(this.isDevelopment, this.envType, name, input) : null;
        } else {
            try {
                return this.getRawClassByteArray(name, allowFromParent);
            } catch (IOException var6) {
                throw new RuntimeException("Failed to load class file for '" + name + "'!", var6);
            }
        }
    }

    private static boolean canTransformClass(String name) {
        name = name.replace('/', '.');
        return !name.startsWith("org.apache.logging.log4j");
    }

    public byte[] getRawClassBytes(String name) throws IOException {
        return this.getRawClassByteArray(name, true);
    }

    private byte[] getRawClassByteArray(String name, boolean allowFromParent) throws IOException {
        name = LoaderUtil.getClassFileName(name);
        URL url = this.classLoader.findResourceFwd(name);
        boolean notFound = false;
        if (url == null) {
            if (!allowFromParent) {
                notFound = true;
            }
            else {
                url = this.parentClassLoader.getResource(name);
                if (!this.isValidParentUrl(url, name)) {
                    if (LOG_CLASS_LOAD) {
                        Log.info(LogCategory.KNOT, "refusing to load class %s at %s from parent class loader", new Object[]{name, getCodeSource(url, name)});
                    }

                    notFound = true;
                }
            }
        }
        byte[] var9;

        if (notFound) {
            if (State.transformNullRawClass) {
                var9 = State.nullRawClassTransformer.transform(name.substring(0, name.length() - 6), null);
            } else {
                return null;
            }
        }
        else {
            InputStream inputStream = url.openStream();
            try {
                int a = inputStream.available();
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream(a < 32 ? '耀' : a);
                byte[] buffer = new byte[8192];

                while (true) {
                    int len;
                    if ((len = inputStream.read(buffer)) <= 0) {
                        var9 = outputStream.toByteArray();
                        break;
                    }

                    outputStream.write(buffer, 0, len);
                }
            } catch (Throwable var11) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Throwable var10) {
                        var11.addSuppressed(var10);
                    }
                }

                throw var11;
            }

            if (inputStream != null) {
                inputStream.close();
            }
        }

        if (State.transformPreMixinRawClass) {
            var9 = State.preMixinRawClassTransformer.transform(name.substring(0, name.length() - 6), var9);
        }

        return var9;
    }

    public static boolean hasRegularCodeSource(URL url) {
        return url.getProtocol().equals("file") || url.getProtocol().equals("jar");
    }

    public static Path getCodeSource(URL url, String fileName) {
        try {
            return LoaderUtil.normalizeExistingPath(UrlUtil.getCodeSource(url, fileName));
        } catch (UrlConversionException var3) {
            throw ExceptionUtil.wrap(var3);
        }
    }

    private static ClassLoader getPlatformClassLoader() {
        try {
            return (ClassLoader)ClassLoader.class.getMethod("getPlatformClassLoader").invoke((Object)null);
        } catch (NoSuchMethodException var1) {
            return new ClassLoader((ClassLoader)null) {
            };
        } catch (ReflectiveOperationException var2) {
            throw new RuntimeException(var2);
        }
    }

    static {
        LOG_CLASS_LOAD_ERRORS = LOG_CLASS_LOAD || System.getProperty("fabric.debug.logClassLoadErrors") != null;
        LOG_TRANSFORM_ERRORS = System.getProperty("fabric.debug.logTransformErrors") != null;
        DISABLE_ISOLATION = System.getProperty("fabric.debug.disableClassPathIsolation") != null;
        PLATFORM_CLASS_LOADER = getPlatformClassLoader();
    }

    public interface ClassLoaderAccess {
        void addUrlFwd(URL var1);

        URL findResourceFwd(String var1);

        Package getPackageFwd(String var1);

        Package definePackageFwd(String var1, String var2, String var3, String var4, String var5, String var6, String var7, URL var8) throws IllegalArgumentException;

        Object getClassLoadingLockFwd(String var1);

        Class<?> findLoadedClassFwd(String var1);

        Class<?> defineClassFwd(String var1, byte[] var2, int var3, int var4, CodeSource var5);

        void resolveClassFwd(Class<?> var1);
    }

    /*static final class Metadata {
        static final Metadata EMPTY = new Metadata((Manifest)null, (CodeSource)null);
        final Manifest manifest;
        final CodeSource codeSource;

        Metadata(Manifest manifest, CodeSource codeSource) {
            this.manifest = manifest;
            this.codeSource = codeSource;
        }
    }*/
}
