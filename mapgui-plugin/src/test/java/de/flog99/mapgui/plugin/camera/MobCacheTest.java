package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing that has to hold about reusing a mob: what it looks like may be a moment old and where it is standing
 * may not. A cache that let a position through would draw a mob where it is not, which is the whole reason a mob is
 * not cached the way a chest is.
 */
class MobCacheTest {

    /** A layer of something, since only the six pose numbers matter here and none of the geometry does. */
    private static EntitySnapshot layer(double x, double y, double z, float bodyYaw, float headYaw, float pitch) {
        return EntitySnapshot.box(x, y, z, bodyYaw, headYaw, 0.6, 1.8, "entity/zombie/zombie")
                .at(x, y, z, bodyYaw, headYaw, pitch);
    }

    private static MobCache.Built built() {
        // As a mob is built: the body follows the head, and whatever is in a hand follows the body and never leans.
        return new MobCache.Built(
                List.of(layer(10, 64, 20, 30, 75, 12)),
                List.of(layer(10, 64, 20, 30, 30, 0)));
    }

    /** The point of the whole class: a reused mob is drawn where it is now, not where it was when it was built. */
    @Test
    void aReusedShapeIsStoodWhereTheMobIsNow() {
        List<EntitySnapshot> drawn = built().standing(11.5, 65, 21.5, 100, 140, -20);

        assertEquals(2, drawn.size());
        for (EntitySnapshot one : drawn) {
            assertEquals(11.5, one.x(), 1e-6);
            assertEquals(65, one.y(), 1e-6);
            assertEquals(21.5, one.z(), 1e-6);
            assertEquals(100, one.bodyYaw(), 1e-6);
        }
    }

    /**
     * The reason the layers are held in two lists. Vanilla poses an item off the arm rather than off the head, so a
     * held one turns with the body and never leans - and a mob standing with its head square to its body looks
     * identical either way right up until it turns, which is exactly when a wrong answer would show.
     */
    @Test
    void whatIsWornFollowsTheHeadAndWhatIsHeldFollowsTheBody() {
        List<EntitySnapshot> drawn = built().standing(0, 0, 0, 100, 140, -20);

        EntitySnapshot worn = drawn.get(0);
        assertEquals(140, worn.headYaw(), 1e-6);
        assertEquals(-20, worn.pitch(), 1e-6);

        EntitySnapshot held = drawn.get(1);
        assertEquals(100, held.headYaw(), 1e-6, "an item in a hand turns with the body");
        assertEquals(0, held.pitch(), 1e-6, "and never leans, whatever the mob is looking at");
    }

    /** The shape itself comes back untouched, since rebuilding one is the cost this exists to avoid. */
    @Test
    void standingOneUpKeepsItsShapeAndItsTexture() {
        MobCache.Built shape = built();
        EntitySnapshot stood = shape.standing(1, 2, 3, 0, 0, 0).getFirst();
        EntitySnapshot as = shape.withHead().getFirst();

        assertEquals(as.texture(), stood.texture());
        assertEquals(as.scale(), stood.scale(), 1e-6);
        assertEquals(as.tint(), stood.tint());
    }

    @Test
    void aShapeComesBackWhileItIsInsideTheWindow() {
        MobCache cache = new MobCache();
        UUID mob = UUID.randomUUID();
        MobCache.Built shape = built();

        cache.put(mob, shape, 0);

        assertSame(shape, cache.get(mob, 1000, 500));
        assertEquals(1, cache.hits());
        assertEquals(1, cache.lookups());
    }

    /** Past the window it is dropped rather than served, and dropped rather than kept for the next asker. */
    @Test
    void aShapePastItsWindowIsGoneRatherThanStale() {
        MobCache cache = new MobCache();
        UUID mob = UUID.randomUUID();

        cache.put(mob, built(), 0);

        assertNull(cache.get(mob, 1000, 1001));
        assertEquals(0, cache.size(), "an entry that was too old to serve is not worth keeping");
        assertEquals(0, cache.hits());
        assertEquals(1, cache.lookups(), "a miss is still a mob the capture asked about");
    }

    /** A mob nothing has asked about in a while has probably died, and there is no event here to say so. */
    @Test
    void expiryDropsWhatNoCaptureCouldStillUse() {
        MobCache cache = new MobCache();
        cache.put(UUID.randomUUID(), built(), 0);

        cache.expire(CameraTuning.Reuse.MOBS.farNanos());
        assertEquals(1, cache.size(), "still inside the longest window anything is allowed");

        cache.expire(CameraTuning.Reuse.MOBS.farNanos() + 1);
        assertEquals(0, cache.size());
    }

    /** The count bound, so a camera flown across a busy world cannot pull every mob it passed into the heap. */
    @Test
    void itHoldsNoMoreThanItsCapacity() {
        MobCache cache = new MobCache();
        for (int i = 0; i < MobCache.CAPACITY * 2; i++) {
            cache.put(UUID.randomUUID(), built(), 0);
        }

        assertEquals(MobCache.CAPACITY, cache.size());
    }

    /**
     * Ramped rather than stepped. A mob crossing a boundary between two flat windows would visibly catch up at the
     * line, which is the one thing a distance rule is supposed to hide.
     */
    @Test
    void theWindowGrowsWithDistanceAndNeverJumps() {
        assertEquals(CameraTuning.Reuse.MOBS.nearNanos(), new MobCache().allowedAgeNanos(0));
        assertEquals(CameraTuning.Reuse.MOBS.nearNanos(), new MobCache().allowedAgeNanos(CameraTuning.Reuse.MOBS.near()));
        assertEquals(CameraTuning.Reuse.MOBS.farNanos(), new MobCache().allowedAgeNanos(CameraTuning.Reuse.MOBS.far()));
        assertEquals(CameraTuning.Reuse.MOBS.farNanos(), new MobCache().allowedAgeNanos(1000));

        long last = 0;
        for (double away = 0; away <= CameraTuning.Reuse.MOBS.far() + 8; away += 0.5) {
            long allowed = new MobCache().allowedAgeNanos(away);
            assertTrue(allowed >= last, "the window shortened at " + away + " blocks");
            last = allowed;
        }

        double middle = new MobCache().allowedAgeNanos((CameraTuning.Reuse.MOBS.near() + CameraTuning.Reuse.MOBS.far()) / 2);
        assertEquals((CameraTuning.Reuse.MOBS.nearNanos() + CameraTuning.Reuse.MOBS.farNanos()) / 2.0, middle, 1e6);
    }

    /** Nothing held for a mob is a miss rather than an empty shape, so the caller builds one. */
    @Test
    void aMobNothingWasEverBuiltForMisses() {
        MobCache cache = new MobCache();

        assertNull(cache.get(UUID.randomUUID(), CameraTuning.Reuse.MOBS.farNanos(), 0));
        assertNotNull(built(), "and building one is what the caller does with that");
    }
}
