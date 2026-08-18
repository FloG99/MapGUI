package de.flog99.mapgui;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the per-tick behavior of {@link WallDisplay#tick} when a wall has no audience:
 * it must cost nothing on the wire - no frames extracted, no bundles opened, no view list churn - and it
 * must keep the wall live. That is the behavior the lazy {@code TileRegions} and the single {@code views()}
 * read guard.
 *
 * <p>The watched path (sendAll on arrival, one bundle per frame, pixel extraction) is covered end to end
 * by {@link WallTilesTest}; driving it here would need a live Bukkit server, which plain unit tests do not
 * have. The unwatched skip sits in front of it, and that is what these guard.
 */
class WallDisplayTickIntegrationTest {

    /** A wall showing one opaque pixel, so a watched tick would move exactly one pixel of data. */
    private static WallDisplay wall(FakeWorld fakeWorld, FakeTransport transport, AtomicInteger paints) {
        WallServices services = new WallServices(transport, null, Runnable::run);
        return new WallDisplay.Builder(services, ignored -> {}, ignored -> {})
                .at(fakeWorld.world(), 0, 64, 0, BlockFace.NORTH)
                .content((painter, bounds, millis) -> {
                    paints.incrementAndGet();
                    painter.pixel(bounds.x(), bounds.y(), (byte) 42);
                })
                .open();
    }

    @Test
    void anUnwatchedWallSendsNothingAndOpensNoBundle() {
        FakeWorld fakeWorld = new FakeWorld();
        FakeTransport transport = new FakeTransport();
        AtomicInteger paints = new AtomicInteger();
        WallDisplay wall = wall(fakeWorld, transport, paints);

        wall.tick(1000L);
        wall.tick(1050L);

        assertEquals(0, transport.updates(), "an empty room must put nothing on the wire");
        assertEquals(0, transport.bundleCount(), "no audience, no bundle");
        assertEquals(0, paints.get(), "unwatched ticks must not invoke content");
    }

    @Test
    void anUnwatchedWallStaysQuietAcrossManyTicks() {
        FakeWorld fakeWorld = new FakeWorld();
        FakeTransport transport = new FakeTransport();
        AtomicInteger paints = new AtomicInteger();
        WallDisplay wall = wall(fakeWorld, transport, paints);

        for (int tick = 0; tick < 20; tick++) {
            wall.tick(1000L + tick * 50L);
        }

        assertEquals(0, transport.updates(), "a whole second of empty ticks must stay silent");
        assertEquals(0, transport.bundleCount());
        assertEquals(0, paints.get(), "unwatched ticks must not invoke content");
    }

    /**
     * The regression guard for the lazy {@code TileRegions}: an empty room must not open the extraction
     * map at all. The observable contract is that nothing is sent and no bundle starts - and that the
     * wall survives, which the subsequent ticks prove.
     */
    @Test
    void tickingAnUnwatchedWallLeavesItOpen() {
        FakeWorld fakeWorld = new FakeWorld();
        FakeTransport transport = new FakeTransport();
        AtomicInteger paints = new AtomicInteger();
        WallDisplay wall = wall(fakeWorld, transport, paints);

        wall.tick(1000L);
        wall.tick(2000L);
        wall.tick(3000L);

        transport.clear();
        wall.tick(4000L);
        assertEquals(0, transport.updates(), "still live, still silent");
        assertEquals(0, paints.get(), "still live, still no content callback");
    }
}
