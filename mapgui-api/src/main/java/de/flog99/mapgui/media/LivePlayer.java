package de.flog99.mapgui.media;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

/**
 * Draws whatever a {@link LiveSource} has right now into whatever box it is given.
 *
 * <p>The counterpart to {@link VideoPlayer}, and deliberately the same shape: same fits, same scaling, same
 * nearest-neighbor. All that differs is that there is no clock - a live picture is not asked what it looked
 * like at a moment, only what it looks like.
 */
public final class LivePlayer {

    private final LiveSource source;
    private VideoPlayer.Fit fit = VideoPlayer.Fit.CONTAIN;

    public LivePlayer(LiveSource source) {
        this.source = source;
    }

    public LivePlayer fit(VideoPlayer.Fit value) {
        this.fit = value;
        return this;
    }

    public LiveSource source() {
        return source;
    }

    /** Nothing at all until the first picture arrives, which leaves whatever is underneath showing. */
    public void paint(Painter painter, Rect bounds) {
        byte[] pixels = source.frame();
        if (pixels == null) return;

        Scaling.paint(painter, bounds, pixels, source.width(), source.height(), fit);
    }
}
