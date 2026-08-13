package de.flog99.mapgui.plugin;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Unit test for the wall snapshot: the per-tick iteration list is rebuilt exactly when the live set
 * changes - never on a quiet tick - and always reflects the live set after an invalidation.
 *
 * <p>This is the mechanism {@link WallRegistry#tick} relies on to avoid a {@code List.copyOf} twenty
 * times a second per wall. The registry itself is not exercised here (that would need a server); the
 * invalidation contract between the two is: every mutation of the live set calls {@code invalidate()}.
 */
class WallSnapshotTest {

    /** Identity objects, so the snapshot's set semantics are tested, not any value equality. */
    private static final class Wall {
        final String name;

        Wall(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static Set<Wall> liveSet() {
        return Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    }

    private static WallSnapshot<Wall> fresh(Set<Wall> live) {
        return new WallSnapshot<>(live);
    }

    @Test
    void firstSnapshotReflectsTheLiveSet() {
        Set<Wall> live = liveSet();
        Wall a = new Wall("a");
        live.add(a);
        WallSnapshot<Wall> snapshot = fresh(live);

        assertEquals(List.of(a), snapshot.snapshot(), "a fresh snapshot is the live contents");
    }

    @Test
    void aQuietTickReturnsTheSameList() {
        Set<Wall> live = liveSet();
        live.add(new Wall("a"));
        WallSnapshot<Wall> snapshot = fresh(live);

        List<Wall> first = snapshot.snapshot();
        assertSame(first, snapshot.snapshot(), "no change, no rebuild - the per-tick allocation is zero");
        assertSame(first, snapshot.snapshot(), "and stays zero across ticks");
    }

    @Test
    void anInsertInvalidatesAndRebuilds() {
        Set<Wall> live = liveSet();
        Wall a = new Wall("a");
        live.add(a);
        WallSnapshot<Wall> snapshot = fresh(live);
        List<Wall> before = snapshot.snapshot();

        Wall b = new Wall("b");
        live.add(b);
        snapshot.invalidate();

        List<Wall> after = snapshot.snapshot();
        assertNotSame(before, after, "a new wall forces a fresh list");
        assertEquals(2, after.size());
        assertEquals(Set.of(a, b), Set.copyOf(after), "both walls, whatever order the live set iterates");
    }

    @Test
    void aRemovalInvalidatesAndRebuilds() {
        Set<Wall> live = liveSet();
        Wall a = new Wall("a");
        Wall b = new Wall("b");
        live.add(a);
        live.add(b);
        WallSnapshot<Wall> snapshot = fresh(live);
        snapshot.snapshot();

        live.remove(a);
        snapshot.invalidate();

        assertEquals(List.of(b), snapshot.snapshot(), "the departed wall is gone from the tick list");
    }

    @Test
    void clearingInvalidatesToEmpty() {
        Set<Wall> live = liveSet();
        live.add(new Wall("a"));
        WallSnapshot<Wall> snapshot = fresh(live);
        snapshot.snapshot();

        live.clear();
        snapshot.invalidate();

        assertEquals(List.of(), snapshot.snapshot(), "closeAll leaves the tick list empty");
    }

    @Test
    void invalidatingWithoutAChangeRebuildsButStaysEqual() {
        Set<Wall> live = liveSet();
        Wall a = new Wall("a");
        live.add(a);
        WallSnapshot<Wall> snapshot = fresh(live);
        List<Wall> first = snapshot.snapshot();

        snapshot.invalidate();
        List<Wall> second = snapshot.snapshot();

        assertNotSame(first, second, "an explicit invalidate does rebuild");
        assertEquals(first, second, "but the contents are the same when the set did not change");
    }
}
