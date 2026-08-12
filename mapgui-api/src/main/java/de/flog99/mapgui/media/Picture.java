package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

/**
 * Draws one still picture into a box.
 *
 * <p>The same scaling and fitting a {@link VideoPlayer} does, without the clock. Anything holding pixels is a
 * {@link Frames} of one frame - a camera capture, a decoded PNG, a single GIF frame - and drawing one through a
 * video player works but reads as though it were about to move.
 */
public final class Picture {

    private Picture() {
    }

    /** Whole picture, bars where it does not reach - see {@link VideoPlayer.Fit#CONTAIN}. */
    public static void paint(Painter painter, Rect bounds, Frames picture) {
        paint(painter, bounds, picture, VideoPlayer.Fit.CONTAIN);
    }

    public static void paint(Painter painter, Rect bounds, Frames picture, VideoPlayer.Fit fit) {
        if (picture == null) return;

        Scaling.paint(painter, bounds, picture.pixels(0), picture.width(), picture.height(), fit);
    }
}
