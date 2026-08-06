package de.flog99.mapgui.camera;

/**
 * Whether the camera can draw, and when it cannot, enough to fix it.
 *
 * <p>Minecraft's textures are not ours to ship, so a camera needs them supplied - see
 * {@code docs/camera.md}. Check this before opening a screen that draws a camera view rather than
 * finding out inside a paint call: a screen can grey out its own button, which is a better answer than
 * any error frame.
 *
 * <p>{@link Loading} is not a failure. It is what the first capture on a fresh server sees while the
 * textures come down, and it settles by itself.
 */
public sealed interface CameraAssets {

    /** Textures are loaded and the camera will draw. */
    record Ready(String minecraftVersion, int blockTextures) implements CameraAssets {
    }

    /** Textures are being fetched. Settles on its own; nothing to do. */
    record Loading(int percent) implements CameraAssets {
    }

    /**
     * The camera cannot draw.
     *
     * @param detail what is wrong, as one sentence
     * @param fix    what the person reading it should do about it
     */
    record Unavailable(Cause cause, String detail, String fix) implements CameraAssets {
    }

    /**
     * Why the camera cannot draw. Separate from the message because a caller may want to react to the
     * kind rather than show the text - {@link #DOWNLOAD_FAILED} on a server with no outbound route is
     * worth handling differently from textures nobody ever installed.
     */
    enum Cause {
        NOT_INSTALLED,
        DOWNLOAD_DISABLED,
        DOWNLOAD_FAILED,
        UNREADABLE,
        VERSION_MISMATCH
    }

    default boolean ready() {
        return this instanceof Ready;
    }
}
