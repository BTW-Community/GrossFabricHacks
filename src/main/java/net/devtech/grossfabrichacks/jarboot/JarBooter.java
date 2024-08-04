package net.devtech.grossfabrichacks.jarboot;

import net.devtech.grossfabrichacks.GrossFabricHacks;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class JarBooter {
	private static final Method ADD_URL;

	/**
	 * Add a URL to the KnotClassLoader, the KnotClassLoader first checks it's URLs before asking parent classloaders for classes,
	 * this allows you to replace library classes, and mix into them (that'll take some creativity on your part until a better api is made)
	 */
	public static void addUrl(final URL url) {
		GrossFabricHacks.UNSAFE_LOADER.addUrlFwd(url);
	}

	static {
		try {
			ADD_URL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
		} catch (Throwable throwable) {
			throw new RuntimeException(throwable);
		}
	}
}
