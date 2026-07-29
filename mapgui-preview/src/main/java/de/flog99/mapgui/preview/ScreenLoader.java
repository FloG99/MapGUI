package de.flog99.mapgui.preview;

import de.flog99.mapgui.Screen;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Loads the previewed screen in a child loader, so a rebuild shows up without restarting. The
 * framework stays in the parent, which is what keeps {@code instanceof Screen} working.
 *
 * <p>Two constraints: the output directory must not also be on the parent classpath, or
 * parent-first delegation wins and the reload silently does nothing; and the loader must stay open
 * while its instance lives, since lambdas resolve lazily.
 */
final class ScreenLoader {

    private final String className;
    private final Path classesDir;

    ScreenLoader(String className, Path classesDir) {
        this.className = className;
        this.classesDir = classesDir;
    }

    String className() {
        return className;
    }

    Path classesDir() {
        return classesDir;
    }

    record Loaded(@Nullable Screen screen, @Nullable URLClassLoader loader, @Nullable String error) {

        static Loaded failed(String error) {
            return new Loaded(null, null, error);
        }
    }

    Loaded load() {
        URLClassLoader loader;
        try {
            loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            return Loaded.failed("Not a usable classes directory: " + classesDir);
        }

        try {
            Object instance = loader.loadClass(className).getDeclaredConstructor().newInstance();
            if (instance instanceof Screen screen) {
                return new Loaded(screen, loader, null);
            }
            close(loader);
            return Loaded.failed(className + " is not a Screen");
        } catch (ClassNotFoundException e) {
            close(loader);
            return Loaded.failed("Not compiled yet: " + className);
        } catch (NoSuchMethodException e) {
            close(loader);
            return Loaded.failed(className + " needs a constructor with no arguments to be previewable");
        } catch (Throwable e) {
            close(loader);
            return Loaded.failed(describe(e));
        }
    }

    private static void close(URLClassLoader loader) {
        try {
            loader.close();
        } catch (Exception ignored) {
            // Nothing useful to do if the loader won't close.
        }
    }

    /** Stack trace trimmed at the preview's own frames, which are never the interesting part. */
    static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException wrapper && wrapper.getCause() != null
                ? wrapper.getCause()
                : error;

        StringBuilder message = new StringBuilder(cause.getClass().getSimpleName());
        if (cause.getMessage() != null) {
            message.append(": ").append(cause.getMessage());
        }

        for (StackTraceElement frame : cause.getStackTrace()) {
            if (frame.getClassName().startsWith("de.flog99.mapgui.preview")) break;
            message.append("\n    at ").append(frame);
        }
        return message.toString();
    }
}
