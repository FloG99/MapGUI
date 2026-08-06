package de.flog99.mapgui.camera;

import de.flog99.mapgui.media.Frames;

/**
 * One captured view, as map palette indices.
 *
 * <p>A {@link Frames} of exactly one frame, which is not a trick: the existing
 * {@link de.flog99.mapgui.media.VideoPlayer} already scales and fits a {@code Frames} into whatever box the
 * layout gave it, so a capture draws with the machinery a GIF uses and needs nothing of its own. Fit modes,
 * scaling and the palette handling all come along for free.
 *
 * <p>Nothing here changes after it is built, so a screen can hold one for as long as it likes and drawing it
 * costs the same as drawing a still - which on the wire means it is sent once and then never again.
 */
public final class CameraShot implements Frames {

    private final int width;
    private final int height;
    private final byte[] pixels;
    private final String minecraftVersion;

    public CameraShot(int width, int height, byte[] pixels, String minecraftVersion) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.minecraftVersion = minecraftVersion;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int count() {
        return 1;
    }

    /** Zero: a capture is one instant and does not run. */
    @Override
    public int durationMs() {
        return 0;
    }

    @Override
    public int indexAt(int millis) {
        return 0;
    }

    @Override
    public byte[] pixels(int index) {
        return pixels;
    }

    /** Which version's textures drew this, which is the one thing worth knowing after the fact. */
    public String minecraftVersion() {
        return minecraftVersion;
    }
}
