package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A chest does not move, so what was drawn for it a moment ago is what would be drawn for it now. These are about
 * the cases where that stops being true.
 */
class BlockEntityCacheTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    /** A still-exact server, so these are only ever about what a viewfinder is allowed. */
    private static BlockEntityCache cache() {
        return new BlockEntityCache(new SnapshotCache(0));
    }

    private static final ReuseWindow WINDOW = CameraTuning.Reuse.BLOCK_ENTITIES;

    private static List<EntitySnapshot> column() {
        return List.of(new EntitySnapshot(0, 0, 0, 0, 0, 0, 1, null, "chest"));
    }

    @Test
    void servesAColumnBackWithinItsWindow() {
        BlockEntityCache cache = cache();
        List<EntitySnapshot> drawn = column();
        cache.put(WORLD, 3, -4, drawn, 1000);

        List<EntitySnapshot> back = cache.get(WORLD, 3, -4, 1000, true, 0);
        assertNotNull(back);
        assertSame(drawn.getFirst(), back.getFirst());
    }

    /** Past the window it is drawn again, or a sign edited an hour ago would still read the old way. */
    @Test
    void refusesOnePastItsWindowAndDropsIt() {
        BlockEntityCache cache = cache();
        cache.put(WORLD, 0, 0, column(), 0);

        assertNull(cache.get(WORLD, 0, 0, WINDOW.nearNanos() + 1, true, 0));
        assertEquals(0, cache.size(), "a column it will not serve is one it should not be holding");
    }

    /**
     * Graded by distance, like everything else the camera holds. A chest at the far end of the range is a couple of
     * pixels, so the same age that is too stale to serve under the camera is fine out there.
     */
    @Test
    void aColumnFurtherOffIsTrustedForLonger() {
        BlockEntityCache cache = cache();
        long tooOldUpClose = WINDOW.nearNanos() + 1;

        cache.put(WORLD, 0, 0, column(), 0);
        assertNotNull(cache.get(WORLD, 0, 0, tooOldUpClose, true, WINDOW.far()), "out at the far end it still stands");

        assertNull(cache.get(WORLD, 0, 0, tooOldUpClose, true, 0), "and under the camera it does not");
    }

    /** A photograph is exact unless a server opted in, the same as the columns behind it. */
    @Test
    void aStillGetsNothingUnlessTheServerAskedForIt() {
        BlockEntityCache cache = cache();
        cache.put(WORLD, 0, 0, column(), 0);

        assertNull(cache.get(WORLD, 0, 0, 0, false, 0));
    }

    @Test
    void aColumnIsOnlyServedForTheWorldItCameFrom() {
        BlockEntityCache cache = cache();
        cache.put(WORLD, 7, 7, column(), 0);

        assertNull(cache.get(OTHER_WORLD, 7, 7, 0, true, 0));
        assertNotNull(cache.get(WORLD, 7, 7, 0, true, 0));
    }

    /** What comes back under that name after an unload is not what was drawn. */
    @Test
    void forgettingAColumnMeansTheNextCaptureDrawsItAgain() {
        BlockEntityCache cache = cache();
        cache.put(WORLD, 2, 2, column(), 0);
        cache.forget(WORLD, 2, 2);

        assertNull(cache.get(WORLD, 2, 2, 0, true, 0));
    }

    /** Bounded by count as well as by age, or a photographer walking a city holds all of it. */
    @Test
    void itStopsGrowingPastItsCapacity() {
        BlockEntityCache cache = cache();
        for (int i = 0; i < BlockEntityCache.CAPACITY * 2; i++) {
            cache.put(WORLD, i, 0, column(), 0);
        }

        assertEquals(BlockEntityCache.CAPACITY, cache.size());
    }
}
