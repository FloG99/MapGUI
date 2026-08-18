package de.flog99.mapgui;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a wall could be on a viewer's screen, which decides whether they are sent frames at all.
 *
 * <p>The wall under test is a six-by-six facing north, so its picture spans x -5..1 and y 64..70 on the plane
 * z=0, centred on (-2, 67, 0). Viewers stand on the negative z side, which is the side the maps face, and all
 * the numbers below are worked from that.
 *
 * <p>The nine points occlusion is traced to fall on a two-block grid: x in {0, -2, -4} by y in {65, 67, 69},
 * with (-2, 67, 0) the middle one and the first to be asked.
 */
class WallSightTest {

    /** Per test rather than shared, since a sight remembers what it has been told about each viewer. */
    private final WallSight sight = new WallSight(WallLayout.anchoredAt(0, 64, 0, BlockFace.NORTH).resized(6, 6));

    /** Empty until a test puts something in the way. Only the occlusion cases touch it. */
    private final FakeWorld world = new FakeWorld();

    /** An eye level with the middle of the wall, {@code back} blocks in front of it, turned {@code yaw} degrees. */
    private Location from(double back, float yaw) {
        return from(back, yaw, 0);
    }

    private Location from(double back, float yaw, float pitch) {
        return new Location(world.world(), -2, 67, -back, yaw, pitch);
    }

    private Location at(double x, double y, double z, float yaw, float pitch) {
        return new Location(world.world(), x, y, z, yaw, pitch);
    }

    private FakePlayer.Watcher watcher() {
        return FakePlayer.watching("viewer", from(5, 0));
    }

    /**
     * The case that decides the shape of the test. Every corner of the wall is three blocks up or down from
     * the eye at a distance of one, so every corner is on its own off screen - the assumed view is a little over
     * two blocks tall at that range. A test asking "is any corner visible" culls this wall while it
     * fills the viewer's monitor. Asking "are all corners off the same side" gets it right: two are above and
     * two below, so neither side rejects.
     */
    @Test
    void aWallFillingTheScreenIsOnIt() {
        assertTrue(sight.onScreen(from(1, 0)), "standing a block from it, looking straight at it");
    }

    @Test
    void aWallAheadIsOnScreenFromAnyReasonableDistance() {
        assertTrue(sight.onScreen(from(3, 0)));
        assertTrue(sight.onScreen(from(10, 0)));
        assertTrue(sight.onScreen(from(40, 0)));
    }

    /** The saving: their back is to it, so every corner is behind the eye. */
    @Test
    void aWallBehindTheViewerIsNot() {
        assertFalse(sight.onScreen(from(5, 180)), "facing directly away");
        assertFalse(sight.onScreen(from(5, 150)));
        assertFalse(sight.onScreen(from(5, -150)));
    }

    /**
     * Turned well past the wall but not away from it. Culled, and symmetric - a sign slip in the screen-right
     * basis would keep one of these and drop the other while both looked plausible on its own.
     */
    @Test
    void aWallWellOutsideTheViewIsNot() {
        assertFalse(sight.onScreen(from(5, 120)));
        assertFalse(sight.onScreen(from(5, -120)));
    }

    /**
     * Deliberately kept. The assumed view is a hundred and sixty-six degrees across, because the server is not
     * told the client's field of view or aspect ratio, so a wall directly to one side is still treated as
     * being at the edge of the screen. Widening the guess costs bandwidth; narrowing it freezes walls people
     * can see.
     */
    @Test
    void aWallOffToTheSideIsGivenTheBenefitOfTheDoubt() {
        assertTrue(sight.onScreen(from(5, 90)));
        assertTrue(sight.onScreen(from(5, -90)));
    }

    /**
     * Looking at the floor or the sky, which is also the pair that exercises the degenerate basis - screen-right
     * comes off the yaw there, because forward crossed with world up vanishes.
     */
    @Test
    void lookingStraightUpOrDownAtAWallSomeWayOffIsNot() {
        assertFalse(sight.onScreen(from(10, 0, 90)), "straight down");
        assertFalse(sight.onScreen(from(10, 0, -90)), "straight up");
    }

    /**
     * One you are standing beside is kept even then. Its far corners are about sixty degrees off a straight-down
     * line of sight, and the view this assumes reaches sixty-five - so on a wide screen with the slider up it
     * really could be along the top edge. Culling it would be the cheaper guess and the wrong one.
     */
    @Test
    void lookingDownBesideAWallStillCatchesIt() {
        assertTrue(sight.onScreen(from(5, 0, 90)));
        assertTrue(sight.onScreen(from(2, 0, 60)));
    }

    // ---- the far side of it ----

    /**
     * The cheapest half of the whole thing. A frame draws nothing from behind and the block it hangs on is in
     * the way regardless, so there is no view from back here at any angle - which is why the sign test comes
     * before the view pyramid rather than being folded into it.
     */
    @Test
    void thereIsNoViewFromBehindAWall() {
        assertFalse(sight.onScreen(from(-6, 180)), "six blocks behind it, facing its back");
        assertFalse(sight.onScreen(from(-6, 0)), "and facing away from its back");
        assertFalse(sight.onScreen(from(-0.1, 180)), "pressed against the back of it");
    }

    /** Edge on is no picture either, and it is the boundary the sign test turns on. */
    @Test
    void thereIsNoViewFromTheWallsOwnPlane() {
        assertFalse(sight.onScreen(from(0, 0)));
    }

    /**
     * A floor wall is the case that forces the eye rather than the feet: standing on one puts your feet
     * exactly on its plane, which a sign test would read as not being in front of it.
     */
    @Test
    void standingOnAFloorWallIsInFrontOfIt() {
        WallSight floor = new WallSight(WallLayout.anchoredAt(0, 64, 0, BlockFace.UP).resized(2, 2));

        assertTrue(floor.onScreen(at(0.5, 65 + 1.62, -0.5, 0, 40)), "looking down at your feet");
        assertFalse(floor.onScreen(at(0.5, 62, -0.5, 0, -40)), "in the room underneath, looking up");
    }

    @Test
    void aCeilingWallIsSeenFromBelow() {
        WallSight ceiling = new WallSight(WallLayout.anchoredAt(0, 64, 0, BlockFace.DOWN).resized(2, 2));

        assertTrue(ceiling.onScreen(at(0.5, 60, 0.5, 0, -50)), "under it, looking up");
        assertFalse(ceiling.onScreen(at(0.5, 70, 0.5, 0, 50)), "in the room above it");
    }

    // ---- the grace period ----

    @Test
    void lookingAtTheWallKeepsTheStreamOn() {
        FakePlayer.Watcher player = watcher();

        assertTrue(sight.streaming(player.player, 0, false));
        assertTrue(sight.streaming(player.player, 10_000, false));
    }

    /**
     * Turning away does not stop the stream at once. Heads turn several times a second and resuming costs a
     * whole frame, so a glance has to be cheaper to ride out than to act on.
     */
    @Test
    void lookingAwayStopsTheStreamOnlyAfterTheGracePeriod() {
        FakePlayer.Watcher player = watcher();

        sight.streaming(player.player, 1_000, false);
        player.eye = from(5, 180);

        assertTrue(sight.streaming(player.player, 1_100, false), "the tick they turned away");
        assertTrue(sight.streaming(player.player, 1_400, false), "still inside the grace period");
        assertFalse(sight.streaming(player.player, 1_500, false), "past it, and now paying nothing");
        assertFalse(sight.streaming(player.player, 9_000, false));
    }

    /** Turning back has to resume, and has to leave the grace period ready for the next time. */
    @Test
    void turningBackResumesAndRearmsTheGracePeriod() {
        FakePlayer.Watcher player = watcher();

        sight.streaming(player.player, 1_000, false);
        player.eye = from(5, 180);
        assertFalse(sight.streaming(player.player, 2_000, false));

        player.eye = from(5, 0);
        assertTrue(sight.streaming(player.player, 2_100, false));

        player.eye = from(5, 180);
        assertTrue(sight.streaming(player.player, 2_200, false), "a fresh grace period rather than the old one");
        assertFalse(sight.streaming(player.player, 2_700, false));
    }

    /**
     * The grace period is for somebody who <i>had</i> the wall on screen, not for anybody who happens to be in
     * range. Walking up to a wall backwards, or standing behind it, has to cost nothing from the very first
     * tick - otherwise every passer-by is handed a keyframe for the grace period to cover, which on a
     * six-by-six is over half a megabyte each.
     */
    @Test
    void aViewerWhoNeverHadItOnScreenIsSentNothingAtAll() {
        FakePlayer.Watcher player = FakePlayer.watching("viewer", from(5, 180));

        assertFalse(sight.streaming(player.player, 1_000, false), "no grace on the first tick either");
        assertFalse(sight.streaming(player.player, 1_100, false));

        player.eye = from(5, 0);
        assertTrue(sight.streaming(player.player, 1_200, false), "and then it starts the moment they turn round");
    }

    /** Walking out of range and back is a fresh start, since their client threw the wall away meanwhile. */
    @Test
    void forgettingAViewerForgetsThatTheyEverSawIt() {
        FakePlayer.Watcher player = watcher();

        sight.streaming(player.player, 1_000, false);
        sight.forget(player.id);

        player.eye = from(5, 180);
        assertFalse(sight.streaming(player.player, 1_050, false), "no grace carried over from before they left");
    }

    // ---- something in the way ----


    /**
     * Layers between the viewer and the wall, each spanning a whole z - a window at one depth, something solid
     * at another. The viewer stands at z=-5 and the picture is at z=0, so these sit between the two and the
     * nearest one ahead of wherever a ray starts is the one it meets.
     */
    private static final class Layers implements FakeWorld.Blocking {

        private final NavigableMap<Integer, Boolean> occludingAt = new TreeMap<>();
        int rays;

        /** Glass, a pane, bars: a ray for a click stops here, a ray for a view carries on. */
        Layers window(int z) {
            occludingAt.put(z, false);
            return this;
        }

        Layers solid(int z) {
            occludingAt.put(z, true);
            return this;
        }

        @Override
        public FakeWorld.Hit hit(Location from, Vector direction, double distance) {
            rays++;
            if (direction.getZ() <= 0) return null;

            for (Map.Entry<Integer, Boolean> layer : occludingAt.entrySet()) {
                double along = (layer.getKey() - from.getZ()) / direction.getZ();
                if (along > 1e-6 && along <= distance) return new FakeWorld.Hit(along, layer.getValue());
            }
            return null;
        }
    }

    @Test
    void aWallWithAClearLineIsWorthSending() {
        FakePlayer.Watcher player = watcher();
        Layers scene = new Layers();
        world.blocking = scene;

        assertTrue(sight.streaming(player.player, 1_000, false));
        assertEquals(1, scene.rays, "the middle is asked first, and one clear sample is the whole answer");
    }

    /** Behind a hill, through a wall, or watching a cinema from the corridor outside it. */
    @Test
    void aWallWithSomethingSolidInFrontOfEveryPartOfItIsNot() {
        FakePlayer.Watcher player = watcher();
        Layers scene = new Layers().solid(-3);
        world.blocking = scene;

        assertFalse(sight.streaming(player.player, 1_000, false), "nothing was on screen, so there is no grace either");
        assertEquals(9, scene.rays, "and it took all nine to be sure");
    }

    /**
     * The reason there are nine rays rather than one. A pillar across the middle of a wall leaves most of the
     * picture in plain view, and a single ray down the line of sight would have called the whole thing hidden.
     */
    @Test
    void aWallVisibleAroundAnObstacleIsStillSent() {
        FakePlayer.Watcher player = watcher();
        world.blocking = spareOnly(-4, 69, 0);

        assertTrue(sight.streaming(player.player, 1_000, false), "one corner of it showing is enough");
    }

    /** Everything solid a block ahead, except a ray aimed at one spared point. Samples are two blocks apart. */
    private static FakeWorld.Blocking spareOnly(double x, double y, double z) {
        Vector spared = new Vector(x, y, z);
        return (from, direction, distance) -> {
            Vector end = from.toVector().add(direction.clone().multiply(distance));
            return end.distance(spared) < 0.5 ? null : new FakeWorld.Hit(Math.min(1, distance), true);
        };
    }

    /**
     * A window is not what hides a wall. Glass, panes, bars, ice and barriers all have full collision, so a ray
     * traced for a click stops dead at them - but a film behind a window is being watched, and freezing it is
     * the error this whole thing is meant to lean away from.
     */
    @Test
    void aWallBehindGlassIsBeingWatched() {
        FakePlayer.Watcher player = watcher();
        world.blocking = new Layers().window(-3);

        assertTrue(sight.streaming(player.player, 1_000, false), "the view goes through the pane and finds nothing else");
    }

    /** More than one pane is no different - a double-glazed cinema is still a cinema. */
    @Test
    void aWallBehindSeveralPanesIsToo() {
        FakePlayer.Watcher player = watcher();
        world.blocking = new Layers().window(-4).window(-3).window(-1);

        assertTrue(sight.streaming(player.player, 1_000, false));
    }

    /**
     * And the case that makes the stepping worth doing at all. A window in front of a solid wall must not excuse
     * the wall behind it: whoever is on the far side of both sees nothing, and stopping at the first thing hit
     * would have called every one of these views clear and streamed to all of them forever.
     */
    @Test
    void aWallBehindGlassAndThenSomethingSolidIsNot() {
        FakePlayer.Watcher player = watcher();
        Layers scene = new Layers().window(-4).solid(-2);
        world.blocking = scene;

        assertFalse(sight.streaming(player.player, 1_000, false), "seen through the glass and stopped by the stone");
        assertTrue(scene.rays > 9, "and every sample was traced past the glass rather than stopped by it");
    }

    /** The same wall behind stone alone is not either, which is what tells a window from a wall. */
    @Test
    void aWallBehindStoneIsNot() {
        FakePlayer.Watcher player = watcher();
        world.blocking = new Layers().solid(-4);

        assertFalse(sight.streaming(player.player, 1_000, false));
    }

    /**
     * Foliage is what actually reaches the limit on how many layers one view is traced through - a tree is a
     * great many leaf blocks and not one of them stops a view. Giving up in favour of sending is the right
     * answer there, since a wall behind leaves is half visible anyway.
     */
    @Test
    void aViewThroughMoreLayersThanTheLimitIsGivenTheBenefitOfTheDoubt() {
        FakePlayer.Watcher player = FakePlayer.watching("viewer", from(30, 0));
        Layers thicket = new Layers();
        for (int z = -29; z < 0; z += 2) thicket.window(z);
        world.blocking = thicket;

        assertTrue(sight.streaming(player.player, 1_000, false), "layer after layer of leaves, none of them opaque");
        assertEquals(12, thicket.rays, "and it stopped looking after a dozen rather than walking to the wall");
    }

    /**
     * Pointing at it overrides all of this, because whoever decided they were pointing at it traced a clear line
     * to the exact pixel under their cursor - which is finer than nine samples of a whole wall can be. A menu
     * glimpsed through a gap the sampling misses must not freeze under the hand using it.
     */
    @Test
    void pointingAtAWallStreamsItWhateverTheSamplesSay() {
        FakePlayer.Watcher player = watcher();
        Layers scene = new Layers().solid(-3);
        world.blocking = scene;

        assertFalse(sight.streaming(player.player, 1_000, false), "every sample of it blocked");
        assertTrue(sight.streaming(player.player, 1_100, true), "but their cursor is on it");
        assertEquals(9, scene.rays, "and settling that cost no rays of its own");
    }

    /**
     * Tracing is the expensive half, and occlusion is a question about where somebody is standing - so somebody
     * who has not moved is answered from what was worked out last time.
     */
    @Test
    void standingStillDoesNotPayForTracingTwice() {
        FakePlayer.Watcher player = watcher();
        Layers scene = new Layers().solid(-3);
        world.blocking = scene;

        sight.streaming(player.player, 1_000, false);
        int afterFirst = scene.rays;

        sight.streaming(player.player, 1_050, false);
        sight.streaming(player.player, 1_100, false);

        assertEquals(afterFirst, scene.rays, "three ticks, one trace");
    }

    /**
     * Moving is what really invalidates a verdict, since occlusion is a question about where somebody stands -
     * so stepping out from behind a pillar has to be traced again rather than held frozen. Blocked from one spot
     * and clear from the other, so a remembered answer would be the wrong one.
     *
     * <p>Not on the very next tick, though. There is a floor under how often this will trace at all, because
     * walking covers a fifth of a block a tick and a crowd moving through a plaza of screens would otherwise
     * re-trace every one of them every tick. Three ticks late to resume, and no rays wasted getting there.
     */
    @Test
    void movingTracesAgainOnceTheFloorIsUp() {
        FakePlayer.Watcher player = watcher();
        world.blocking = (from, direction, distance) -> from.getX() > 10 ? new FakeWorld.Hit(Math.min(1, distance), true) : null;

        player.eye = at(20, 67, -5, 0, 0);
        assertFalse(sight.streaming(player.player, 1_000, false), "the wall on screen but nothing of it reachable");

        player.eye = from(5, 0);
        assertFalse(sight.streaming(player.player, 1_050, false), "moved, but too soon to have asked again");
        assertTrue(sight.streaming(player.player, 1_200, false), "and now traced afresh, and streaming");
    }

    /**
     * And taken again eventually even if they have not moved, because somebody can build in front of a screen.
     * The grace period still sits in front of it, so going quiet takes both to run out.
     */
    @Test
    void aRememberedVerdictDoesNotOutlastTheWorldChanging() {
        FakePlayer.Watcher player = watcher();
        world.blocking = spareOnly(-2, 67, 0);

        assertTrue(sight.streaming(player.player, 1_000, false));

        world.blocking = new Layers().solid(-3);
        assertTrue(sight.streaming(player.player, 1_200, false), "still trusting what it worked out a moment ago");
        assertTrue(sight.streaming(player.player, 1_600, false), "asked again and hidden now, but inside the grace period");
        assertFalse(sight.streaming(player.player, 1_800, false), "and past that, nothing");
    }
}
