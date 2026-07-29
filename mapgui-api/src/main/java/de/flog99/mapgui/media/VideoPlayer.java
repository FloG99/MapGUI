package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

/**
 * Draws {@link Frames} into whatever box the layout gave it.
 *
 * <p>Nothing is cached, because there is nothing left worth caching: frames arrive already matched to
 * the palette, so painting is a scale and a copy rather than a palette lookup per pixel.
 */
public final class VideoPlayer {

    /** What to do when the video is not the same shape as the box it is being drawn in. */
    public enum Fit {

        /**
         * Whole picture, bars where it does not reach. The default, since losing content is worse than
         * losing a few pixels of a 128 pixel canvas.
         */
        CONTAIN,

        /** Fills the box and crops whatever hangs over the edges. */
        COVER,

        /** Fills the box exactly and distorts to do it. */
        STRETCH
    }

    private final Frames frames;
    private Fit fit = Fit.CONTAIN;

    public VideoPlayer(Frames frames) {
        this.frames = frames;
    }

    public VideoPlayer fit(Fit value) {
        this.fit = value;
        return this;
    }

    public Frames frames() {
        return frames;
    }

    /**
     * Draws whichever frame belongs at {@code millis}, looping for anything past the end.
     *
     * <p>Nearest neighbor on purpose. Smoothing an already tiny picture would mean blending palette
     * entries back into colors and matching them again, which costs more than the smoothing is worth on
     * a canvas this size.
     */
    public void paint(Painter painter, Rect bounds, int millis) {
        Rect target = fitInto(bounds);
        if (target.width() <= 0 || target.height() <= 0) return;

        byte[] pixels = frames.pixels(frames.indexAt(millis));

        for (int y = 0; y < target.height(); y++) {
            int row = y * frames.height() / target.height() * frames.width();
            for (int x = 0; x < target.width(); x++) {
                byte pixel = pixels[row + x * frames.width() / target.width()];
                // Skipped, not drawn as the transparent index: a see-through GIF should show the
                // background under it, and on a wall there is nothing under it to show.
                if (pixel == Frames.TRANSPARENT) continue;

                // Anything hanging outside the node is clipped by the painter, which is what crops COVER.
                painter.pixel(target.x() + x, target.y() + y, pixel);
            }
        }
    }

    /** The box the picture actually occupies, which for {@link Fit#CONTAIN} is centered inside. */
    private Rect fitInto(Rect bounds) {
        if (fit == Fit.STRETCH) return bounds;

        double byWidth = bounds.width() / (double) frames.width();
        double byHeight = bounds.height() / (double) frames.height();
        double scale = fit == Fit.COVER ? Math.max(byWidth, byHeight) : Math.min(byWidth, byHeight);

        int width = Math.max(1, (int) Math.round(frames.width() * scale));
        int height = Math.max(1, (int) Math.round(frames.height() * scale));
        return new Rect(bounds.x() + (bounds.width() - width) / 2, bounds.y() + (bounds.height() - height) / 2, width, height);
    }
}
