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

    /** Draws whichever frame belongs at {@code millis}, looping for anything past the end. */
    public void paint(Painter painter, Rect bounds, int millis) {
        Scaling.paint(painter, bounds, frames.pixels(frames.indexAt(millis)), frames.width(), frames.height(), fit);
    }
}
