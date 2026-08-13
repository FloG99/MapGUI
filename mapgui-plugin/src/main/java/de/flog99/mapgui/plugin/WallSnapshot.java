package de.flog99.mapgui.plugin;

import de.flog99.mapgui.WallDisplay;

import java.util.List;
import java.util.Set;

/**
 * The set of open walls, as a list for per-tick iteration, rebuilt only when the wall set changes.
 *
 * <p>A tick runs twenty times a second and a wall that sits still must not pay for a
 * {@code List.copyOf} each time, so the list is cached behind an invalidation flag. The source set is
 * live - {@link #invalidate()} is called from the same places the set is mutated - and {@link #snapshot}
 * hands out the cached copy, refilled from the live set on the next call after an invalidation.
 *
 * <p>Not thread-safe: the plugin mutates and ticks on the main thread only. The network thread reads the
 * live set directly, never this.
 *
 * <p>Sized because the snapshot is keyed by a live {@code Set<T>} and has nothing to do with walls; a
 * set of anything can be snapshot the same way.
 */
final class WallSnapshot<T> {

    private final Set<T> live;
    private List<T> snapshot = List.of();
    private boolean dirty = true;

    WallSnapshot(Set<T> live) {
        this.live = live;
    }

    /** Called from wherever {@link #live} is mutated, so the next {@link #snapshot} rebuilds. */
    void invalidate() {
        dirty = true;
    }

    /**
     * The walls to tick this time: the cached copy when nothing changed, otherwise a fresh one.
     *
     * @return the same list until {@link #invalidate} is called, so the per-tick iteration allocates nothing
     */
    List<T> snapshot() {
        if (dirty) {
            snapshot = List.copyOf(live);
            dirty = false;
        }
        return snapshot;
    }
}
