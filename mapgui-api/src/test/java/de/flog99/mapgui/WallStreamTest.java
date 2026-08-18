package de.flog99.mapgui;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a wall actually puts on the wire as a viewer turns towards it and away again - the half of the
 * off-screen cull that is not geometry.
 *
 * <p>The promise being guarded is that the cull can never cost more than it saves. Pausing a viewer is free,
 * resuming one costs a whole frame, and so a wall that did not change while they were away must resume for
 * nothing at all. Get that wrong and a menu wall becomes more expensive than it was before there was a cull.
 *
 * <p>A one-by-one wall facing north, so a whole frame is one map and one number.
 */
class WallStreamTest {

    /** Every pixel of a single map, which is what resuming a viewer costs at most. */
    private static final int WHOLE_FRAME = 128 * 128;

    /** The same pixels every frame, so the wall goes still after the first paint. */
    private static final WallContent STILL = (painter, bounds, millis) -> painter.pixel(0, 0, (byte) 42);

    /** One pixel that changes every hundred milliseconds, so every frame has something to send. */
    private static final WallContent MOVING =
            (painter, bounds, millis) -> painter.pixel(0, 0, (byte) (40 + (millis / 100) % 8));

    private final FakeWorld world = new FakeWorld();
    private final FakeTransport transport = new FakeTransport();

    /** Three blocks out on the side the maps face, level with the middle of them. */
    private Location facing() {
        return new Location(world.world(), 0.5, 65, -3, 0f, 0f);
    }

    /** The same spot, turned right round. */
    private Location turnedAway() {
        return new Location(world.world(), 0.5, 65, -3, 180f, 0f);
    }

    private WallDisplay wall(WallContent content) {
        return wall(content, true);
    }

    private WallDisplay wall(WallContent content, boolean cullOffScreen) {
        WallServices services = new WallServices(transport, null, Runnable::run);
        return new WallDisplay.Builder(services, ignored -> {}, ignored -> {})
                .at(world.world(), 0, 64, 0, BlockFace.NORTH)
                .fps(10)
                .cullOffScreen(cullOffScreen)
                .content(content)
                .open();
    }

    private FakePlayer.Watcher watcher(Location eye) {
        FakePlayer.Watcher player = FakePlayer.watching("viewer", eye);
        world.players = List.of(player.player);
        return player;
    }

    /** Ticks every hundred milliseconds inclusive, which is one frame each at ten frames a second. */
    private static void ticks(WallDisplay wall, long from, long to) {
        for (long now = from; now <= to; now += 100) wall.tick(now);
    }

    @Test
    void arrivingInFrontOfAWallSendsTheWholePicture() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(STILL);

        wall.tick(1000);

        assertEquals(1, transport.updates());
        assertEquals(WHOLE_FRAME, transport.pixelsSent());
        assertTrue(wall.sees(player.player));
    }

    /**
     * Arriving with your back to it costs nothing, and the picture is delivered the moment you turn round. A
     * corridor of people walking past the back of a cinema screen is the case: a keyframe each on arrival
     * would be half a megabyte apiece for a wall none of them ever looked at.
     */
    @Test
    void arrivingWithYourBackToAWallCostsNothingUntilYouTurnRound() {
        FakePlayer.Watcher player = watcher(turnedAway());
        WallDisplay wall = wall(STILL);

        wall.tick(1000);
        assertEquals(0, transport.updates(), "in range, facing away, and not a packet");

        player.eye = facing();
        wall.tick(1100);
        assertEquals(WHOLE_FRAME, transport.pixelsSent(), "and never left with blank maps once they look");
    }

    /**
     * Standing behind a wall is a pause and not an eviction. It has to be: {@code depthOf} measures against
     * the wall's <i>plane</i>, which runs on past its edges, so walking round a wall hung on a narrow pillar
     * crosses it in open air. Evicting there would close a per-player screen and take its state with it, and
     * charge a whole frame on the way back - every time somebody walked round the thing.
     */
    @Test
    void standingBehindAWallPausesRatherThanEvicting() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = new Location(world.world(), 0.5, 65, 4, 0f, 0f);
        ticks(wall, 1100, 3000);

        assertTrue(wall.sees(player.player), "still a viewer, so their screen and its state survive");
        assertEquals(1, wall.viewerCount());
    }

    /** And behind it, they are sent nothing - which is the whole point of testing the plane at all. */
    @Test
    void aViewerBehindAWallIsSentNothing() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = new Location(world.world(), 0.5, 65, 4, 0f, 0f);
        ticks(wall, 1100, 1500);
        transport.clear();

        ticks(wall, 1600, 3000);

        assertEquals(0, transport.updates());
    }

    /** A glance away is ridden out rather than paid for, since resuming costs more than the glance saves. */
    @Test
    void aGlanceAwayKeepsTheStreamRunning() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = turnedAway();
        transport.clear();

        wall.tick(1100);
        wall.tick(1200);

        assertEquals(2, transport.updates(), "still streaming, one small delta a frame");
    }

    /** And once they have plainly settled on looking elsewhere, nothing at all. */
    @Test
    void lookingAwayStopsTheStreamOnceTheGracePeriodIsUp() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = turnedAway();
        ticks(wall, 1100, 1500);
        transport.clear();

        ticks(wall, 1600, 3000);

        assertEquals(0, transport.updates(), "fourteen frames of video, and not a packet for them");
    }

    /** Still a viewer throughout, so their maps and their frames were never taken away from them. */
    @Test
    void aPausedViewerIsStillAViewer() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = turnedAway();
        ticks(wall, 1100, 3000);

        assertTrue(wall.sees(player.player), "paused, not evicted");
        assertEquals(1, wall.viewerCount());
    }

    /** Turning back to a wall that moved on without them costs one frame, because the deltas they missed are gone. */
    @Test
    void turningBackToAWallThatMovedCostsOneWholeFrame() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        player.eye = turnedAway();
        ticks(wall, 1100, 3000);
        transport.clear();

        player.eye = facing();
        wall.tick(3100);

        assertEquals(1, transport.updates());
        assertEquals(WHOLE_FRAME, transport.pixelsSent());
    }

    /**
     * The property the whole design turns on. Nothing changed while they were away, so there is nothing to
     * catch up on and resuming is free - which is what makes the cull safe to leave on for a menu or a sign,
     * where it would otherwise trade a stream that costs nothing for a keyframe every time a head turned.
     */
    @Test
    void turningBackToAStillWallCostsNothing() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(STILL);

        wall.tick(1000);
        player.eye = turnedAway();
        ticks(wall, 1100, 3000);
        transport.clear();

        player.eye = facing();
        ticks(wall, 3100, 3500);

        assertEquals(0, transport.updates(), "a still wall must not pay for anybody looking around");
    }

    /**
     * A wall behind a hill, or a cinema seen from the corridor outside it. The same pause as turning away, so
     * the viewer keeps their screen and pays nothing while the view is blocked.
     *
     * <p>Something being built in front of a wall is the slowest of these to be noticed, and the two delays
     * stack: the traced verdict is trusted for half a second, and the grace period runs half a second from the
     * last frame that went out. So a second, worst case, and it errs by sending too much.
     */
    @Test
    void aWallWithSomethingSolidInFrontOfItIsSentNothing() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING);

        wall.tick(1000);
        world.blocking = (from, direction, distance) -> new FakeWorld.Hit(Math.min(1, distance), true);
        ticks(wall, 1100, 2100);
        transport.clear();

        ticks(wall, 2200, 3000);

        assertEquals(0, transport.updates(), "nothing gets through, so nothing is sent");
        assertTrue(wall.sees(player.player), "and they are still a viewer");
    }

    /** Turned off, a wall streams to whoever is in range however they are facing - which is what it used to do. */
    @Test
    void cullingOffScreenCanBeTurnedOff() {
        FakePlayer.Watcher player = watcher(facing());
        WallDisplay wall = wall(MOVING, false);

        wall.tick(1000);
        player.eye = turnedAway();
        transport.clear();

        ticks(wall, 1100, 1500);

        assertEquals(5, transport.updates(), "one delta a frame, regardless of where they are looking");
    }
}
