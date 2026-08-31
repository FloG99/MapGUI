package de.flog99.mapgui;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who a wall shows itself to, who may work it, how far it can be pointed at, and what its frames look like.
 *
 * <p>All four are decisions a wall makes per player per tick, and none of them is visible in a screenshot -
 * a wall that is being sent to somebody it should not be looks exactly like one that is not.
 *
 * <p>A one-by-one wall facing north with a viewer three blocks out in front of it, the same geometry
 * {@code WallStreamTest} uses.
 */
class WallPolicyTest {

    private final FakeWorld world = new FakeWorld();
    private final FakeTransport transport = new FakeTransport();

    /** One pixel that changes every hundred milliseconds, so there is always something to send. */
    private static final WallContent MOVING =
            (painter, bounds, millis) -> painter.pixel(0, 0, (byte) (40 + (millis / 100) % 8));

    /** Three blocks out on the side the maps face, level with the middle of the one map, looking straight at it. */
    private Location facing() {
        return new Location(world.world(), 0.5, 64.5, -3, 0f, 0f);
    }

    private WallDisplay wall(Consumer<WallDisplay.Builder> tuning) {
        WallServices services = new WallServices(transport, null, Runnable::run);
        WallDisplay.Builder builder = new WallDisplay.Builder(services, ignored -> {}, ignored -> {})
                .at(world.world(), 0, 64, 0, BlockFace.NORTH)
                .fps(10)
                .content(MOVING);
        tuning.accept(builder);

        WallDisplay wall = builder.open();
        assertNotNull(wall);
        return wall;
    }

    private FakePlayer.Watcher watcher() {
        FakePlayer.Watcher player = FakePlayer.watching("viewer", facing());
        world.players = List.of(player.player);
        return player;
    }

    // ---- visibleTo ----

    /**
     * Nothing at all rather than a viewer sent no pixels: an item frame with no picture behind it is a grey
     * square, so withholding only the pixels would leave the wall visibly there.
     */
    @Test
    void somebodyTheWallIsNotVisibleToIsNotAViewerAtAll() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.visibleTo(viewer -> false));

        wall.tick(1000);

        assertEquals(0, wall.viewerCount());
        assertFalse(wall.sees(player.player));
        assertEquals(0, transport.updates());
    }

    @Test
    void somebodyTheWallIsVisibleToIsShownItAsUsual() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.visibleTo(viewer -> true));

        wall.tick(1000);

        assertEquals(1, wall.viewerCount());
        assertTrue(wall.sees(player.player));
        assertTrue(transport.updates() > 0);
    }

    // ---- controlledBy ----

    @Test
    void aWallIsPointedAtByDefault() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> {});

        wall.tick(1000);

        assertTrue(wall.measureAim(player.player) > 0);
    }

    /**
     * Not measured at all for somebody who may not work it, which is what keeps their click from being
     * claimed: an unmeasured wall is never the nearest, so it never takes their aim and never swallows a
     * right-click they meant for the world.
     */
    @Test
    void somebodyWhoMayNotControlTheWallNeverPointsAtIt() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.controlledBy(viewer -> false));

        wall.tick(1000);

        assertEquals(-1, wall.measureAim(player.player));
        assertFalse(wall.isAiming(player.player));
        assertFalse(wall.click(player.player, Click.RIGHT));
    }

    /** Seeing and working it are separate, which is the whole point of the pair. */
    @Test
    void aWallCanBeVisibleToEveryoneAndControlledByNobody() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.controlledBy(viewer -> false));

        wall.tick(1000);

        assertTrue(wall.sees(player.player));
        assertEquals(-1, wall.measureAim(player.player));
    }

    // ---- reach ----

    @Test
    void aWallOutOfReachIsNotPointedAt() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.reach(1));

        wall.tick(1000);

        assertEquals(-1, wall.measureAim(player.player));
    }

    @Test
    void aWallInsideItsReachIsPointedAt() {
        FakePlayer.Watcher player = watcher();
        WallDisplay wall = wall(builder -> builder.reach(8));

        wall.tick(1000);

        assertTrue(wall.measureAim(player.player) > 0);
    }

    // ---- frame cosmetics ----

    @Test
    void framesAreGlowingAndInvisibleUnlessSaidOtherwise() {
        wall(builder -> {});

        assertEquals(FrameStyle.DEFAULT, transport.style());
    }

    @Test
    void eachCosmeticIsSetWithoutDisturbingTheOthers() {
        wall(builder -> builder.glowing(false));
        assertEquals(new FrameStyle(false, true, 0), transport.style());

        wall(builder -> builder.invisible(false));
        assertEquals(new FrameStyle(true, false, 0), transport.style());

        wall(builder -> builder.itemRotation(3));
        assertEquals(new FrameStyle(true, true, 3), transport.style());

        wall(builder -> builder.glowing(false).invisible(false).itemRotation(7));
        assertEquals(new FrameStyle(false, false, 7), transport.style());
    }

    @Test
    void anItemRotationOutsideTheEighthsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> wall(builder -> builder.itemRotation(FrameStyle.ROTATIONS)));
        assertThrows(IllegalArgumentException.class, () -> wall(builder -> builder.itemRotation(-1)));
        assertThrows(IllegalArgumentException.class, () -> new FrameStyle(true, true, 8));
    }
}
