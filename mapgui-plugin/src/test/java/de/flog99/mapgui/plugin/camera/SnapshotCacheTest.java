package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.ChunkFrustum;
import org.bukkit.ChunkSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one part of the capture path that can be wrong rather than merely slow: a column served after the blocks in it
 * changed is a photograph of a world that no longer exists. So the tests are mostly about what it refuses to serve.
 */
class SnapshotCacheTest {

    private static final UUID WORLD = UUID.randomUUID();
    private static final UUID OTHER_WORLD = UUID.randomUUID();

    /** The cache never asks a snapshot anything, so anything with its own identity stands in for one. */
    private static ChunkSnapshot column() {
        return (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
                new Class<?>[]{ChunkSnapshot.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> null;
                });
    }

    /**
     * The default, and the one the shipped config chooses: reuse is the only shortcut the camera has that can show a
     * block as it was a moment ago, so for a <b>photograph</b> it stays opt-in. That one is kept.
     */
    @Test
    void aZeroLifetimeServesNoStill() {
        SnapshotCache cache = new SnapshotCache(0);

        assertFalse(cache.enabledForStills());
        cache.put(WORLD, 1, 1, column(), 0);

        assertNull(cache.get(WORLD, 1, 1, 0, cache.allowedAgeNanos(false, 0)), "off means off, even at the instant of the put");
        // Counted even though it was never eligible. The rate is meant to read as "how much of what this capture
        // wanted did it get for free", and leaving the ineligible columns out would make an off cache read 0/0.
        assertEquals(1, cache.lookups());
    }

    /**
     * A live view reuses whatever the stills setting says, because the argument against reuse barely applies to it:
     * being wrong lasts until the next frame, and the next frame is coming. Without this a viewfinder copies a
     * player's whole render distance several times a second and spends its entire budget doing it.
     */
    @Test
    void aLiveViewReusesEvenWithStillsTurnedOff() {
        SnapshotCache cache = new SnapshotCache(0);
        ChunkSnapshot held = column();
        cache.put(WORLD, 1, 1, held, 0);

        assertTrue(cache.enabled());
        assertSame(held, cache.get(WORLD, 1, 1, CameraTuning.Reuse.CHUNKS.farNanos(), cache.allowedAgeNanos(true, (int) CameraTuning.Reuse.CHUNKS.far())));
        assertNull(cache.get(WORLD, 1, 1, 0, cache.allowedAgeNanos(false, 0)), "and the photograph is still exact");
    }

    /**
     * The whole point of grading it: a column at the photographer's feet is most of the picture and is never
     * reused, where one on the horizon is a couple of pixels and may be a second behind.
     */
    @Test
    void howStaleAColumnMayBeDependsOnHowFarAwayItIs() {
        SnapshotCache cache = new SnapshotCache(0);

        assertEquals(CameraTuning.Reuse.CHUNKS.nearNanos(), cache.allowedAgeNanos(true, 0), "under the camera");
        assertEquals(CameraTuning.Reuse.CHUNKS.nearNanos(), cache.allowedAgeNanos(true, (int) CameraTuning.Reuse.CHUNKS.near()));
        assertEquals(CameraTuning.Reuse.CHUNKS.farNanos(), cache.allowedAgeNanos(true, (int) CameraTuning.Reuse.CHUNKS.far()));
        assertEquals(CameraTuning.Reuse.CHUNKS.farNanos(), cache.allowedAgeNanos(true, 200), "and no further than that");

        long middle = cache.allowedAgeNanos(true, ((int) CameraTuning.Reuse.CHUNKS.near() + (int) CameraTuning.Reuse.CHUNKS.far()) / 2);
        assertTrue(middle > CameraTuning.Reuse.CHUNKS.nearNanos() && middle < CameraTuning.Reuse.CHUNKS.farNanos(),
                "the ramp is a ramp, not a step");
    }

    /** A photograph is flat at whatever the server opted into: no frame follows it to correct anything. */
    @Test
    void aStillIsNotGradedByDistance() {
        SnapshotCache cache = new SnapshotCache(0);

        assertEquals(0, cache.allowedAgeNanos(false, 0));
        assertEquals(0, cache.allowedAgeNanos(false, 200), "distance buys a still nothing");
    }

    /** Past its own window a live view copies again, or a viewfinder would drift arbitrarily far behind the world. */
    @Test
    void aLiveViewStillRefusesOnePastItsOwnWindow() {
        SnapshotCache cache = new SnapshotCache(0);
        cache.put(WORLD, 1, 1, column(), 0);

        assertNull(cache.get(WORLD, 1, 1, CameraTuning.Reuse.CHUNKS.farNanos() + 1, cache.allowedAgeNanos(true, (int) CameraTuning.Reuse.CHUNKS.far())));
    }

    /** A server that asked for longer gets longer for both, since the live window is a floor rather than a ceiling. */
    @Test
    void aConfiguredLifetimeLongerThanTheLiveOneAppliesToBoth() {
        long twoSeconds = CameraTuning.Reuse.CHUNKS.farNanos() * 4;
        SnapshotCache cache = new SnapshotCache(twoSeconds);
        ChunkSnapshot held = column();
        cache.put(WORLD, 1, 1, held, 0);

        assertSame(held, cache.get(WORLD, 1, 1, twoSeconds, cache.allowedAgeNanos(false, 0)));
        assertSame(held, cache.get(WORLD, 1, 1, twoSeconds, cache.allowedAgeNanos(true, (int) CameraTuning.Reuse.CHUNKS.far())));
    }

    @Test
    void servesAColumnBackWithinItsLifetime() {
        SnapshotCache cache = new SnapshotCache();
        ChunkSnapshot held = column();

        cache.put(WORLD, 3, -4, held, 1000);

        assertSame(held, cache.get(WORLD, 3, -4, 1000, cache.allowedAgeNanos(false, 0)));
        assertSame(held, cache.get(WORLD, 3, -4, 1000 + SnapshotCache.LIFETIME_NANOS, cache.allowedAgeNanos(false, 0)));
    }

    @Test
    void refusesOnePastItsLifetimeAndDropsIt() {
        SnapshotCache cache = new SnapshotCache();
        cache.put(WORLD, 0, 0, column(), 1000);

        assertNull(cache.get(WORLD, 0, 0, 1001 + SnapshotCache.LIFETIME_NANOS, cache.allowedAgeNanos(false, 0)));
        assertTrue(cache.size() == 0, "a column it will not serve is a column it should not be holding");
    }

    @Test
    void aColumnIsOnlyServedForTheWorldItCameFrom() {
        SnapshotCache cache = new SnapshotCache();
        ChunkSnapshot held = column();
        cache.put(WORLD, 7, 7, held, 0);

        assertNull(cache.get(OTHER_WORLD, 7, 7, 0, cache.allowedAgeNanos(false, 0)));
        assertSame(held, cache.get(WORLD, 7, 7, 0, cache.allowedAgeNanos(false, 0)));
    }

    @Test
    void neighbouringColumnsAreNotEachOther() {
        SnapshotCache cache = new SnapshotCache();
        ChunkSnapshot here = column();
        ChunkSnapshot next = column();
        cache.put(WORLD, 5, 9, here, 0);
        cache.put(WORLD, 9, 5, next, 0);

        assertSame(here, cache.get(WORLD, 5, 9, 0, cache.allowedAgeNanos(false, 0)));
        assertSame(next, cache.get(WORLD, 9, 5, 0, cache.allowedAgeNanos(false, 0)));
        assertNull(cache.get(WORLD, 5, -9, 0, cache.allowedAgeNanos(false, 0)));
    }

    @Test
    void forgettingAColumnMeansTheNextCaptureCopiesItAgain() {
        SnapshotCache cache = new SnapshotCache();
        cache.put(WORLD, 2, 2, column(), 0);
        cache.forget(WORLD, 2, 2);

        assertNull(cache.get(WORLD, 2, 2, 0, cache.allowedAgeNanos(false, 0)));
    }

    @Test
    void expiringWalksOffTheOldWithoutTouchingTheRest() {
        SnapshotCache cache = new SnapshotCache();
        for (int i = 0; i < 8; i++) {
            cache.put(WORLD, i, 0, column(), i * (SnapshotCache.LIFETIME_NANOS / 4));
        }

        long now = 7 * (SnapshotCache.LIFETIME_NANOS / 4);
        cache.expire(now);

        assertNull(cache.get(WORLD, 0, 0, now, cache.allowedAgeNanos(false, 0)));
        assertNull(cache.get(WORLD, 2, 0, now, cache.allowedAgeNanos(false, 0)));
        assertNotNull(cache.get(WORLD, 4, 0, now, cache.allowedAgeNanos(false, 0)));
        assertNotNull(cache.get(WORLD, 7, 0, now, cache.allowedAgeNanos(false, 0)));
    }

    /** A player flying around must not pull the world into the heap, whatever the lifetime says. */
    @Test
    void neverHoldsMoreThanItsCapacity() {
        SnapshotCache cache = new SnapshotCache();
        for (int i = 0; i < SnapshotCache.CAPACITY * 4; i++) {
            cache.put(WORLD, i, i, column(), 0);
            assertTrue(cache.size() <= SnapshotCache.CAPACITY, "held " + cache.size() + " after " + i);
        }
    }

    /** Eviction is by use rather than by age, so a column every capture wants is not the one thrown out. */
    @Test
    void evictsTheColumnNoCaptureHasAskedFor() {
        SnapshotCache cache = new SnapshotCache();
        ChunkSnapshot wanted = column();
        cache.put(WORLD, 0, 0, wanted, 0);

        for (int i = 1; i <= SnapshotCache.CAPACITY; i++) {
            cache.get(WORLD, 0, 0, 0, cache.allowedAgeNanos(false, 0));
            cache.put(WORLD, i, i, column(), 0);
        }

        assertSame(wanted, cache.get(WORLD, 0, 0, 0, cache.allowedAgeNanos(false, 0)));
        assertNull(cache.get(WORLD, 1, 1, 0, cache.allowedAgeNanos(false, 0)), "the one nothing asked for again");
    }

    /**
     * What the cache is for, measured the way a camera actually gets used: the same shot twice, then one from a few
     * blocks along. Walks the same square {@link WorldCapture} walks, with the same frustum deciding it, so the
     * number is the real overlap between two frames rather than a guess at it.
     */
    @Test
    void aSecondShotFromNearlyTheSamePlaceIsMostlyReuse() {
        double same = reuse(0, 0, 0, 0);
        double stepped = reuse(3, 1, 0, 0);
        double turned = reuse(0, 0, 12, 0);
        double aboutFace = reuse(0, 0, 180, 0);

        assertTrue(same > 0.99, "the identical shot reused " + same);
        assertTrue(stepped > 0.8, "three blocks along reused " + stepped);
        assertTrue(turned > 0.7, "twelve degrees round reused " + turned);
        assertTrue(aboutFace < 0.1, "turned all the way round reused " + aboutFace);
    }

    /** The share of a second capture's columns that the first one had already copied. */
    private static double reuse(double movedX, double movedZ, float turned, float pitched) {
        SnapshotCache cache = new SnapshotCache();
        capture(cache, 100.5, 70, 100.5, 34, 12);

        long before = cache.lookups();
        long hitsBefore = cache.hits();
        int wanted = capture(cache, 100.5 + movedX, 70, 100.5 + movedZ, 34 + turned, 12 + pitched);

        assertTrue(cache.lookups() - before == wanted, "every column of the second frame is a lookup");
        return (double) (cache.hits() - hitsBefore) / wanted;
    }

    /** One capture's worth of the loop in {@link WorldCapture}, with every chunk taken as loaded. */
    private static int capture(SnapshotCache cache, double x, double y, double z, float yaw, float pitch) {
        int distance = 192;
        CameraView view = new CameraView(x, y, z, yaw, pitch, 70, distance);
        ChunkFrustum frustum = new ChunkFrustum(view, -64, 319);

        int radius = (distance >> 4) + 1;
        int originX = ((int) Math.floor(x) >> 4) - radius;
        int originZ = ((int) Math.floor(z) >> 4) - radius;
        int across = radius * 2 + 1;
        int copied = 0;

        cache.expire(0);
        for (int cz = 0; cz < across; cz++) {
            for (int cx = 0; cx < across; cx++) {
                int chunkX = originX + cx;
                int chunkZ = originZ + cz;
                if (!frustum.mightSee(chunkX, chunkZ)) {
                    continue;
                }

                copied++;
                if (cache.get(WORLD, chunkX, chunkZ, 0, cache.allowedAgeNanos(false, 0)) == null) {
                    cache.put(WORLD, chunkX, chunkZ, column(), 0);
                }
            }
        }
        return copied;
    }
}
