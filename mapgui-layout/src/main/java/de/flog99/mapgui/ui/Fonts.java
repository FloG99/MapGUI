package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TrueType faces shipped with a plugin, loaded once.
 *
 * <p>{@link AwtFont} has done TrueType at any size all along. What it left to every caller was the loading: a
 * resource stream, a checked {@code IOException}, a fallback for when the file is not there, and a static field to
 * keep the result in - because a font caches a rasterized glyph per character, so one built per call rasterizes
 * the alphabet again on every frame. That is four things to get right for one line of design intent.
 *
 * <pre>{@code
 * private static final TextFont TITLE = Fonts.trueType("font/title.ttf", 16f);
 * }</pre>
 *
 * <p>Cached, so the field is a convenience rather than a requirement and two screens naming the same file share
 * one glyph cache. <b>No face is bundled</b>: a font is somebody's licensed work, and a library has no business
 * deciding whose.
 *
 * <p>A file that is missing or unreadable gives the JVM's own sans-serif at the same size rather than throwing,
 * because a screen with the wrong typeface still reads and a screen that threw while building does not.
 *
 * <p>Always anti-aliased, since a plugin reaching for its own face is doing it for the look. Reach past this to
 * {@link AwtFont#load} for the on-or-off version, which is what a pixel font at eight pixels wants.
 */
@ApiStatus.Experimental
public final class Fonts {

    private static final Logger LOG = System.getLogger(Fonts.class.getName());

    /** What stands in when a face cannot be read. Every JVM has it, at every size. */
    private static final String FALLBACK_FAMILY = "SansSerif";

    /**
     * One cache per classloader, held <b>weakly</b> for the same reason {@link Images} holds one: a static map
     * keeping a plugin's loader alive would keep every class that plugin ever loaded alive with it.
     */
    private static final Map<ClassLoader, Map<Resource, TextFont>> BY_RESOURCE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Faces that came from a stream, keyed by what was in it. Nothing here points at a plugin's classes. */
    private static final Map<Face, TextFont> BY_CONTENT = new ConcurrentHashMap<>();

    private record Resource(String path, float size) {
    }

    private record Face(String digest, float size) {
    }

    private Fonts() {
    }

    /**
     * A face from the calling plugin's own resources, at the size you want it.
     *
     * @param path where it sits in the jar, such as {@code "font/title.ttf"}. A leading slash is allowed and ignored
     * @param size in points, which on a map is very nearly pixels of line height
     * @return the face, or the JVM's sans-serif at the same size if it could not be read
     */
    public static TextFont trueType(String path, float size) {
        ClassLoader loader = Images.callerLoader();
        Resource key = new Resource(Images.normalized(path), size);

        return BY_RESOURCE.computeIfAbsent(loader, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(key, wanted -> fromResource(loader, wanted));
    }

    /**
     * The same for a face that did not come out of a jar - one in the server's own folder, one downloaded.
     *
     * <p>Keyed on what the stream held rather than on where it came from, since a stream has no identity to key
     * on and reading the same file twice should not rasterize the alphabet twice. Which means the bytes are read
     * in full here; a font file is tens of kilobytes, and the alternative is a glyph cache per call.
     *
     * <p>The stream is closed either way.
     */
    public static TextFont trueType(InputStream stream, float size) {
        byte[] bytes;
        try (stream) {
            bytes = stream.readAllBytes();
        } catch (IOException e) {
            LOG.log(Logger.Level.WARNING, "Could not read a font stream", e);
            return fallback(size);
        }

        return BY_CONTENT.computeIfAbsent(new Face(digestOf(bytes), size), wanted -> parse(bytes, size, "a stream"));
    }

    private static TextFont fromResource(ClassLoader loader, Resource wanted) {
        try (InputStream stream = loader.getResourceAsStream(wanted.path())) {
            if (stream == null) {
                LOG.log(Logger.Level.WARNING, "No font at {0} in the calling plugin''s resources", wanted.path());
                return fallback(wanted.size());
            }
            return parse(stream.readAllBytes(), wanted.size(), wanted.path());
        } catch (IOException e) {
            LOG.log(Logger.Level.WARNING, "Could not read the font at " + wanted.path(), e);
            return fallback(wanted.size());
        }
    }

    private static TextFont parse(byte[] bytes, float size, String what) {
        try {
            return AwtFont.load(new ByteArrayInputStream(bytes), size, true);
        } catch (IOException e) {
            LOG.log(Logger.Level.WARNING, "Not a font this JVM can read: " + what, e);
            return fallback(size);
        }
    }

    /** Rounded, because a family the JVM already has is asked for in whole points. */
    private static TextFont fallback(float size) {
        return AwtFont.named(FALLBACK_FAMILY, Font.PLAIN, Math.max(1, Math.round(size)), true);
    }

    /**
     * A digest rather than the bytes themselves, so the cache does not hold every font file it was ever shown.
     *
     * <p>And a real digest rather than a hash code, since a collision here would hand back the wrong typeface -
     * rare enough never to be noticed in testing and impossible to explain when it happens.
     */
    private static String digestOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM has SHA-256", e);
        }
    }
}
