package net.fabricmc.loader.impl.launch.knot;

import net.devtech.grossfabrichacks.unsafe.UnsafeUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.logging.Logger;

import static java.util.logging.Logger.getLogger;

public class UnsafeKnotClassLoader extends KnotClassLoaderHack {
    public static final HashMap<String, Class<?>> classes = new HashMap<>();
    public static final Class<net.fabricmc.loader.impl.launch.knot.KnotClassLoader> superclass = KnotClassLoader.class;
    public static final ClassLoader applicationClassLoader;

    public static final KnotClassDelegate delegate;
    public static final URLClassLoader parent;

    private static final Logger LOGGER = getLogger("GrossFabricHacks/UnsafeKnotClassLoader");
    private static final Method delegate_getMetadata;
    private static final Method delegate_getPostMixinClassByteArray;


    static {
        try {
            delegate_getMetadata = KnotClassDelegateHack.class.getDeclaredMethod("getMetadata", String.class);
            delegate_getMetadata.setAccessible(true);
            delegate_getPostMixinClassByteArray = KnotClassDelegateHack.class.getDeclaredMethod("getPostMixinClassByteArray", String.class, boolean.class);
            delegate_getPostMixinClassByteArray.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public UnsafeKnotClassLoader(final boolean isDevelopment, final EnvType envType, final GameProvider provider) {
        super(isDevelopment, envType, provider);
    }

    public Class<?> defineClass(final String name, final byte[] bytes) {
        final Class<?> klass = UnsafeUtil.defineClass(name, bytes, this, null);

        classes.put(name, klass);

        return klass;
    }

    public Class<?> getLoadedClass(final String name) {
        final Class<?> klass = super.findLoadedClass(name);

        if (klass == null) {
            return classes.get(name);
        }

        return klass;
    }

    //@Override
    public boolean isClassLoaded(final String name) {
        synchronized (super.getClassLoadingLock(name)) {
            return super.findLoadedClass(name) != null || classes.get(name) != null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        synchronized (this.getClassLoadingLock(name)) {
            Class<?> klass = classes.get(name);

            if (klass == null) {
                klass = this.findLoadedClass(name);

                if (klass == null) {
                    try {
                        if (/*!name.startsWith("com.google.gson.") &&*/ !name.startsWith("java.")) {
                            final byte[] input;
                            try {
                                // TODO check boolean
                                input = (byte[]) delegate_getPostMixinClassByteArray.invoke(delegate, name, false);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                throw new RuntimeException(e);
                            }

                            if (input != null) {
                                final int pkgDelimiterPos = name.lastIndexOf('.');

                                if (pkgDelimiterPos > 0) {
                                    final String pkgString = name.substring(0, pkgDelimiterPos);

                                    if (this.getPackage(pkgString) == null) {
                                        this.definePackage(pkgString, null, null, null, null, null, null, null);
                                    }
                                }

                                //klass = super.defineClass(name, input, 0, input.length, delegate.getMetadata(name, parent.getResource(delegate.getClassFileName(name))).codeSource);
                                try {
                                    klass = super.defineClass(name, input, 0, input.length, ((KnotClassDelegate.Metadata) delegate_getMetadata.invoke(delegate, name)).codeSource);
                                } catch (IllegalAccessException | InvocationTargetException e) {
                                    throw new RuntimeException(e);
                                }
                            } else {

                                klass = applicationClassLoader.loadClass(name);
                            }
                        } else {
                            klass = applicationClassLoader.loadClass(name);
                        }
//                    } catch (final ClassFormatError formatError) {
//                        LOGGER.warn("A ClassFormatError was encountered while attempting to define {}; resorting to unsafe definition.", name);
//
//                        klass = UnsafeUtil.defineClass(name, delegate.getPostMixinClassByteArray(name));
//                    }
                    } catch (final ClassNotFoundException | RuntimeException e) {
                        LOGGER.warning("Failed to load class " + name + " from KnotClassLoader, CREATE A DUMMY CLASS");
                        ClassNode klassNode = new ClassNode();
                        klassNode.name = name.replace('.', '/');
                        klassNode.superName = "java/lang/Object";
                        klassNode.version = 61;
                        klassNode.access = 33;
                        String var10001 = name.substring(name.lastIndexOf(46) + 1);
                        klassNode.sourceFile = var10001 + ".java";
                        ClassWriter writer = new ClassWriter(3);
                        klassNode.accept(writer);
                        throw e;
                    }
                }

                classes.put(name, klass);
            }

            if (resolve) {
                this.resolveClass(klass);
            }

            return klass;
        }
    }

    static {
        try {
            final Class<UnsafeKnotClassLoader> thisClass = UnsafeKnotClassLoader.class;
            final ClassLoader knotClassLoader = Thread.currentThread().getContextClassLoader();
            applicationClassLoader = thisClass.getClassLoader();

            long knotClassLoaderKlass = UnsafeUtil.getKlass(knotClassLoader);
            UnsafeUtil.unsafeCast(knotClassLoader, UnsafeUtil.getKlassFromClass(UnsafeKnotClassLoader.class));

            classes.put(superclass.getName(), superclass);
            classes.put(thisClass.getName(), thisClass);

            for (final String name : new String[]{
                "net.devtech.grossfabrichacks.State",
                "net.devtech.grossfabrichacks.unsafe.UnsafeUtil$FirstInt",
                "net.devtech.grossfabrichacks.unsafe.UnsafeUtil"}) {
                classes.put(name, Class.forName(name, false, applicationClassLoader));
            }

            for (final String name : new String[]{
                "net.devtech.grossfabrichacks.transformer.asm.AsmClassTransformer",
                "net.devtech.grossfabrichacks.transformer.asm.RawClassTransformer",
                "net.devtech.grossfabrichacks.transformer.TransformerApi",
                "org.spongepowered.asm.mixin.transformer.HackedMixinTransformer",
            }) {
                classes.put(name, UnsafeUtil.findAndDefineClass(name, applicationClassLoader));
            }


            delegate = ((UnsafeKnotClassLoader) knotClassLoader).getDelegate();

            UnsafeUtil.unsafeCast(delegate, UnsafeUtil.getKlassFromClass(KnotClassDelegateHack.class));

            parent = (URLClassLoader) knotClassLoader.getParent();
        } catch (final Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }
}
