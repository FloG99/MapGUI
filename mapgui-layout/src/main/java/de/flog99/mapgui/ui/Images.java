package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Artwork read out of the jar it is shipped in, once.
 *
 * <p>Every consumer that draws a picture has so far written the same three things: an {@code ImageIO.read} of a
 * resource stream, a {@code try} around it because that throws, and a map to keep the result in because a screen
 * is rebuilt on every state change and decoding a PNG per frame is not free. This is those three things.
 *
 * <p><b>Whose resources</b> is read off the stack rather than passed in, because the answer is always "the caller's":
 * a screen naming {@code "icons/pickaxe.png"} means the one in its own plugin. Nothing has to be handed a class or a
 * classloader to say so.
 *
 * <p>A path that is not there gives null, which {@link Bitmap} draws as nothing at all - so a missing asset shows
 * the node's background rather than throwing on a server somebody else is running. Misses are cached too: a typo
 * would otherwise cost a failed resource lookup on every frame, forever.
 */
@ApiStatus.Experimental
public final class Images {

    /** Deep enough for a caller behind a helper or two of their own, and short enough to be free. */
    private static final int FRAMES = 16;

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Logger LOG = System.getLogger(Images.class.getName());

    /**
     * One cache per classloader, and the classloader is held <b>weakly</b>.
     *
     * <p>Which is the whole reason this is nested rather than keyed on a {@code (loader, path)} record: a static map
     * strongly holding a plugin's classloader would keep every class that plugin ever loaded alive after it is
     * disabled. Nothing in a decoded image points back at the loader, so once the plugin goes its images go with it.
     */
    private static final Map<ClassLoader, Map<String, Optional<BufferedImage>>> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private Images() {
    }

    /**
     * A picture from the calling plugin's own resources, decoded once and kept.
     *
     * @param path where it sits in the jar, such as {@code "icons/pickaxe.png"}. A leading slash is allowed and
     *             ignored, since that is how the same path is written for {@code Class#getResourceAsStream}
     * @return null if there is nothing there, or nothing {@link ImageIO} can read
     */
    @Nullable
    public static BufferedImage of(String path) {
        String cleaned = normalized(path);
        ClassLoader loader = callerLoader();
        return cached(loader, cleaned, () -> read(loader, cleaned)).orElse(null);
    }

    /**
     * The same cache, filled by drawing rather than by reading a file.
     *
     * <p>For art that is computed - a colour wheel, a dial face, a chart nobody ships as a PNG. The name is a
     * cache key rather than a path and never touches the jar, so it only has to be unique within the plugin.
     *
     * @param name what to file it under. Anything, so long as two different pictures never share one
     * @param art  run at most once. Returning null is allowed and is remembered as "nothing to draw"
     */
    @Nullable
    public static BufferedImage of(String name, Supplier<BufferedImage> art) {
        return cached(callerLoader(), name, () -> Optional.ofNullable(art.get())).orElse(null);
    }

    private static Optional<BufferedImage> cached(ClassLoader loader, String key, Supplier<Optional<BufferedImage>> make) {
        Map<String, Optional<BufferedImage>> images = CACHE.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>());
        return images.computeIfAbsent(key, ignored -> make.get());
    }

    /** Empty rather than throwing, and said once each way, since the answer is what gets cached. */
    private static Optional<BufferedImage> read(ClassLoader loader, String path) {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                LOG.log(Logger.Level.WARNING, "No image resource at {0}", path);
                return Optional.empty();
            }
            return Optional.ofNullable(ImageIO.read(stream));
        } catch (IOException e) {
            LOG.log(Logger.Level.WARNING, "Could not read the image at " + path, e);
            return Optional.empty();
        }
    }

    /** A classloader wants a path from the root of the jar; a class wants one relative to itself. Take either. */
    static String normalized(String path) {
        int at = 0;
        while (at < path.length() && path.charAt(at) == '/') at++;

        return path.substring(at);
    }

    /**
     * Whose resources to look in: the first frame on the stack that is not MapGUI's own.
     *
     * <p>Told apart by the classloader rather than by the package, since that is the thing being looked for anyway -
     * every class of MapGUI's shares MapGUI's loader, and a plugin has its own. Which also means a caller inside
     * MapGUI, or a unit test sharing one loader with everything, correctly ends up with that loader.
     *
     * <p>Shared with {@link Fonts}, which asks the same question of the same stack.
     */
    static ClassLoader callerLoader() {
        ClassLoader own = Images.class.getClassLoader();
        return WALKER.walk(frames -> frames
                .limit(FRAMES)
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(Class::getClassLoader)
                .filter(loader -> loader != null && loader != own)
                .findFirst()
                .orElse(own)
        );
    }
}
