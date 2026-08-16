package de.flog99.mapgui.plugin;

import java.util.List;
import java.util.Set;

/**
 * A snapshot of a live {@code Set<T>} as an immutable list, rebuilt only when the set changes.
 *
 * <p>A tick runs twenty times a second and a set that sits still must not pay for a
 * {@code List.copyOf} each time, so the list is cached behind an invalidation flag. The source set is
 * live - {@link #invalidate()} is called from the same places the set is mutated - and {@link #snapshot}
 * hands out the cached copy, refilled from the live set on the next call after an invalidation.
 *
 * <p>Not thread-safe: the caller mutates and reads on the same thread only.
 *
 * <p>Generic because the snapshot is keyed by a live {@code Set<T>}; a set of anything can be
 * snapshotted the same way.
 */
final class SetSnapshot<T> {

    private final Set<T> live;
    private List<T> snapshot = List.of();
    private boolean dirty = true;

    SetSnapshot(Set<T> live) {
        this.live = live;
    }

    /** Called from wherever {@link #live} is mutated, so the next {@link #snapshot} rebuilds. */
    void invalidate() {
        dirty = true;
    }

    /**
     * The cached list when nothing changed, otherwise a fresh copy of the live set.
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
