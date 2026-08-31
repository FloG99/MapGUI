package de.flog99.mapgui;

import de.flog99.mapgui.media.Frames;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A short animation sent once and then played by pointing the maps at it.
 *
 * <p>The client keeps a picture for every map id it has been sent and forgets none of them, so an animation of known
 * length can be sent as that many complete copies and then played by pointing the maps at one. A 2x2 wall at 10 fps
 * costs 640 KB a second to stream and about 800 bytes a second to flip.
 *
 * <p>What it costs instead is memory, in two places: a painted surface per step on the server, and 16 KB per map per
 * step in each viewer's client - a twelve step 3x3 wall is 1.7 MB per client. Hence the cap, and hence being asked
 * for rather than assumed.
 *
 * <p>Only for content that repeats exactly: the steps are painted once, when the wall opens. Anything that
 * reacts to the world, the time of day or who is looking will be frozen as it was at that moment.
 */
final class WallLoop {

    /**
     * Enough for a sign, a logo or a short clip, and a limit on what a viewer's client is asked to hold.
     *
     * <p>Past this, streaming the frames costs less than storing them - and the wall would take a noticeable
     * moment to arrive, since every step goes out at once when someone walks up.
     */
    static final int MAX_STEPS = 32;

    private final MapSurface[] steps;
    private final int stepMs;

    /** Which step each viewer is currently pointed at, so a frame that has not changed sends nothing. */
    private final Map<UUID, Integer> showing = new HashMap<>();

    private WallLoop(MapSurface[] steps, int stepMs) {
        this.steps = steps;
        this.stepMs = stepMs;
    }

    /**
     * Paints the content once at each point in its loop.
     *
     * <p>Painted at {@code i * period / steps}, so the steps land evenly. An animation whose own frames are
     * unevenly spaced is therefore sampled rather than followed exactly - ask for as many steps as it has
     * frames and it comes out right for anything with a steady frame rate.
     */
    static WallLoop paint(WallLayout layout, WallContent content, int wanted, long periodMs) {
        int count = Math.clamp(wanted, 1, MAX_STEPS);
        MapSurface[] steps = new MapSurface[count];

        for (int i = 0; i < count; i++) {
            MapSurface surface = new MapSurface(layout.pixelWidth(), layout.pixelHeight());
            surface.fill(Frames.TRANSPARENT);

            content.paint(surface.painter(), surface.bounds(), i * periodMs / count);
            steps[i] = surface;
        }
        return new WallLoop(steps, (int) Math.max(1, periodMs / count));
    }

    /**
     * Sends every step to a viewer who has just arrived, and points them at the one that is due.
     *
     * <p>All of it at once, which is the price of admission: a twelve step 2x2 wall is 768 KB in one go. It
     * pays for itself in a couple of seconds of playback and costs nothing thereafter.
     */
    void start(Player player, WallTiles tiles, long now, TileRegions frame) {
        for (int step = 0; step < steps.length; step++) {
            tiles.sendAll(player, steps[step], step, frame);
        }

        int step = stepAt(now);
        showing.put(player.getUniqueId(), step);
        tiles.showLayer(player, step);
    }

    /** Points a viewer at the step that is due now, if it is not the one they are already showing. */
    void tick(Player player, WallTiles tiles, long now) {
        int step = stepAt(now);
        Integer current = showing.get(player.getUniqueId());
        if (current != null && current == step) return;

        showing.put(player.getUniqueId(), step);
        tiles.showLayer(player, step);
    }

    void forget(UUID player) {
        showing.remove(player);
    }

    int stepCount() {
        return steps.length;
    }

    MapSurface step(int index) {
        return steps[index];
    }

    /** Off the wall clock rather than each viewer's own, so everybody in the room sees the same frame. */
    private int stepAt(long now) {
        return Math.floorMod(now / stepMs, steps.length);
    }
}
