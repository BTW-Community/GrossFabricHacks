package net.fabricmc.loader.impl.launch.knot;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.Enumeration;
import java.util.Objects;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.game.GameProvider;

class KnotClassLoaderHack extends SecureClassLoader implements KnotClassDelegate.ClassLoaderAccess, KnotClassDelegateHack.ClassLoaderAccess {
    private final DynamicURLClassLoader urlLoader = (DynamicURLClassLoader)this.getParent();
    private final ClassLoader originalLoader = this.getClass().getClassLoader();
    private final KnotClassDelegate<KnotClassLoader> delegate;

    KnotClassLoaderHack(boolean isDevelopment, EnvType envType, GameProvider provider) {
        super(new DynamicURLClassLoader(new URL[0]));
        this.delegate = new KnotClassDelegate(isDevelopment, envType, this, this.originalLoader, provider);
    }

    KnotClassDelegate<?> getDelegate() {
        return this.delegate;
    }

    public URL getResource(String name) {
        Objects.requireNonNull(name);
        URL url = this.urlLoader.getResource(name);
        if (url == null) {
            url = this.originalLoader.getResource(name);
        }

        return url;
    }

    public URL findResource(String name) {
        Objects.requireNonNull(name);
        return this.urlLoader.findResource(name);
    }

    public InputStream getResourceAsStream(String name) {
        Objects.requireNonNull(name);
        InputStream inputStream = this.urlLoader.getResourceAsStream(name);
        if (inputStream == null) {
            inputStream = this.originalLoader.getResourceAsStream(name);
        }

        return inputStream;
    }

    public Enumeration<URL> getResources(String name) throws IOException {
        Objects.requireNonNull(name);
        Enumeration<URL> resources = this.urlLoader.getResources(name);
        return !resources.hasMoreElements() ? this.originalLoader.getResources(name) : resources;
    }

    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        return this.delegate.loadClass(name, resolve);
    }

    protected Class<?> findClass(String name) throws ClassNotFoundException {
        return this.delegate.tryLoadClass(name, false);
    }

    public void addUrlFwd(URL url) {
        this.urlLoader.addURL(url);
    }

    public URL findResourceFwd(String name) {
        return this.urlLoader.findResource(name);
    }

    public Package getPackageFwd(String name) {
        return super.getPackage(name);
    }

    public Package definePackageFwd(String name, String specTitle, String specVersion, String specVendor, String implTitle, String implVersion, String implVendor, URL sealBase) throws IllegalArgumentException {
        return super.definePackage(name, specTitle, specVersion, specVendor, implTitle, implVersion, implVendor, sealBase);
    }

    public Object getClassLoadingLockFwd(String name) {
        return super.getClassLoadingLock(name);
    }

    public Class<?> findLoadedClassFwd(String name) {
        return super.findLoadedClass(name);
    }

    public Class<?> defineClassFwd(String name, byte[] b, int off, int len, CodeSource cs) {
        return super.defineClass(name, b, off, len, cs);
    }

    public void resolveClassFwd(Class<?> cls) {
        super.resolveClass(cls);
    }

    static {
        registerAsParallelCapable();
    }

    private static final class DynamicURLClassLoader extends URLClassLoader {
        private DynamicURLClassLoader(URL[] urls) {
            super(urls, new DummyClassLoader());
        }

        public void addURL(URL url) {
            super.addURL(url);
        }

        static {
            registerAsParallelCapable();
        }
    }
}
