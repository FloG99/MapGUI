package de.flog99.mapgui;

import de.flog99.mapgui.media.LivePlayer;
import de.flog99.mapgui.media.LiveSource;
import de.flog99.mapgui.media.VideoPlayer;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;

/** Whatever a wall shows. Given the whole wall as one canvas, with no idea it is several maps. */
@FunctionalInterface
public interface WallContent {

    /** {@code millis} counts from when the wall started showing, so it only ever goes forwards. */
    void paint(Painter painter, Rect bounds, long millis);

    /**
     * A looping video.
     *
     * <p>The wrap is here rather than in the player because a wall can be up for months, and a millisecond
     * count that far out overflows the int a frame index is looked up by.
     */
    static WallContent video(VideoPlayer video) {
        int duration = Math.max(1, video.frames().durationMs());
        return (painter, bounds, millis) ->
                video.paint(painter, bounds, Math.floorMod(millis, duration));
    }

    /**
     * Something arriving as it plays - a video file too long to decode into memory, or a live stream.
     *
     * <p>No clock is passed on, because there is none to pass: the source runs at its own speed on its own
     * thread and the wall paints whatever is current. Give the wall the frame rate you want to <i>send</i> at
     * and the source will keep up or repeat itself, whichever way round the two rates fall.
     */
    static WallContent live(LiveSource source) {
        LivePlayer player = new LivePlayer(source);
        return (painter, bounds, millis) -> player.paint(painter, bounds);
    }
}
