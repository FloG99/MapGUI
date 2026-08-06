package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

/**
 * Drawing one already-quantized picture into a box, scaled to fit it.
 *
 * <p>Shared because a decoded animation and a live stream are the same problem once the pixels exist: how big
 * to draw them and where. What differs is only where the pixels came from.
 */
final class Scaling {

    private Scaling() {
    }

    /**
     * Nearest neighbor on purpose. Smoothing an already tiny picture would mean blending palette entries back
     * into colors and matching them again, which costs more than the smoothing is worth on a canvas this size.
     */
    static void paint(Painter painter, Rect bounds, byte[] pixels, int width, int height, VideoPlayer.Fit fit) {
        Rect target = fitInto(bounds, width, height, fit);
        if (target.width() <= 0 || target.height() <= 0) return;

        for (int y = 0; y < target.height(); y++) {
            int row = y * height / target.height() * width;
            for (int x = 0; x < target.width(); x++) {
                byte pixel = pixels[row + x * width / target.width()];
                // Skipped, not drawn as the transparent index: a see-through GIF should show the
                // background under it, and on a wall there is nothing under it to show.
                if (pixel == Frames.TRANSPARENT) continue;

                // Anything hanging outside the node is clipped by the painter, which is what crops COVER.
                painter.pixel(target.x() + x, target.y() + y, pixel);
            }
        }
    }

    /** The box the picture actually occupies, which for {@link VideoPlayer.Fit#CONTAIN} is centered inside. */
    static Rect fitInto(Rect bounds, int width, int height, VideoPlayer.Fit fit) {
        if (fit == VideoPlayer.Fit.STRETCH) return bounds;

        double byWidth = bounds.width() / (double) width;
        double byHeight = bounds.height() / (double) height;
        double scale = fit == VideoPlayer.Fit.COVER ? Math.max(byWidth, byHeight) : Math.min(byWidth, byHeight);

        int scaledWidth = Math.max(1, (int) Math.round(width * scale));
        int scaledHeight = Math.max(1, (int) Math.round(height * scale));
        return new Rect(
                bounds.x() + (bounds.width() - scaledWidth) / 2,
                bounds.y() + (bounds.height() - scaledHeight) / 2,
                scaledWidth,
                scaledHeight
        );
    }
}
