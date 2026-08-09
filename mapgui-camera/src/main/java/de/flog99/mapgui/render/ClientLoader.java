package de.flog99.mapgui.render;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * A client jar loaded beside the server's own classes: child first for {@code net.minecraft}, parent first for
 * everything else.
 *
 * <p>Without the split a server's own {@code net.minecraft} classes shadow the jar's and what comes back is half
 * from each. With it the jar is a self-consistent copy and only the shared libraries are borrowed - which is the
 * whole trick, since those libraries are on a server already and none of this needs a dependency.
 */
final class ClientLoader extends URLClassLoader {

    ClientLoader(String name, URL[] urls, ClassLoader parent) {
        super(name, urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith("net.minecraft.")) {
            return super.loadClass(name, resolve);
        }

        synchronized (getClassLoadingLock(name)) {
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                try {
                    found = findClass(name);
                } catch (ClassNotFoundException e) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(found);
            }
            return found;
        }
    }
}
