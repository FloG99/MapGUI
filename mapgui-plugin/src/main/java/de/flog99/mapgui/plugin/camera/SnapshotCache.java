package de.flog99.mapgui.plugin.camera;

import org.bukkit.ChunkSnapshot;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Chunk snapshots kept between captures, for as long as they can be trusted - which is not long.
 *
 * <p>Copying the world is the expensive half of a capture and the half that has to happen on the main thread, and
 * two captures a moment apart want almost exactly the same columns.
 *
 * <p><b>The lifetime is a timeout rather than a guarantee, which is why this is off unless a server asks for it.</b>
 * There is no signal to invalidate on: the block events miss pistons, fluid, growth, explosions and every other
 * plugin, and a snapshot carries light as well as blocks, so a torch placed in the chunk next door changes this one
 * without ever touching it. Every other fast path here is exact - the frustum only drops columns no ray reaches, the
 * empty-space skip only walks past what is provably empty - and this one trades staleness for a copy. It earns its
 * place in a burst of captures and nowhere else; a lifetime of zero disables it entirely.
 *
 * <p>Bounded by count as well as by age, or a player flying around with a camera pulls the world into the heap:
 * least-recently-used past {@link #CAPACITY}, about one wide capture's worth.
 *
 * <p>What crosses to the trace thread is the {@link ChunkSnapshot} itself, a read-only copy already read by several
 * threads inside one frame. The map is only touched on the capture tick but is locked anyway, since an
 * access-ordered map reordered by two threads does not come back stale, it comes back broken.
 */
final class SnapshotCache {

    /**
     * How long a copied column may be served for. Twenty ticks: long enough that a burst of captures pays for the
     * world once, short enough that nothing in it can be far wrong.
     */
    static final long LIFETIME_NANOS = TimeUnit.MILLISECONDS.toNanos(1000);

    /**
     * Columns held at most, which caps the cache at a few tens of megabytes.
     *
     * <p>At range 192 and the default field of view, a level frame wants around 120 columns and the steepest around
     * 220, so this covers a capture and a few steps of walking. A capture wide enough to want more than this does
     * not break, it just stops reusing the columns it copied first - the eviction is least-recently-used, and inside
     * one frame nothing asks twice.
     */
    static final int CAPACITY = 256;

    private record Key(UUID world, int chunkX, int chunkZ) {
    }

    private record Held(ChunkSnapshot snapshot, long takenAt) {
    }

    /** Access-ordered, so the eldest entry really is the one no capture has wanted for longest. */
    private final Map<Key, Held> held = new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Held> eldest) {
            return size() > CAPACITY;
        }
    };

    private final long lifetimeNanos;

    private long hits;
    private long lookups;

    SnapshotCache() {
        this(LIFETIME_NANOS);
    }

    /**
     * @param lifetimeNanos how long a column may be served for, or zero to keep nothing at all
     */
    SnapshotCache(long lifetimeNanos) {
        this.lifetimeNanos = Math.max(0, lifetimeNanos);
    }

    /** Whether this will hold anything, for the log line that says how a capture is being taken. */
    boolean enabled() {
        return lifetimeNanos > 0;
    }

    /**
     * @param now {@code System.nanoTime()}, read once per capture rather than once per column so that every column
     *            of one frame is judged against the same instant
     * @return null if this column was never copied, or was copied too long ago to stand behind
     */
    synchronized ChunkSnapshot get(UUID world, int chunkX, int chunkZ, long now) {
        if (!enabled()) return null;
        lookups++;

        Key key = new Key(world, chunkX, chunkZ);
        Held entry = held.get(key);
        if (entry == null) return null;

        if (now - entry.takenAt() > lifetimeNanos) {
            held.remove(key);
            return null;
        }

        hits++;
        return entry.snapshot();
    }

    synchronized void put(UUID world, int chunkX, int chunkZ, ChunkSnapshot snapshot, long now) {
        if (!enabled()) return;
        held.put(new Key(world, chunkX, chunkZ), new Held(snapshot, now));
    }

    /** For a chunk that has unloaded: whatever comes back under that name later is not what was copied. */
    synchronized void forget(UUID world, int chunkX, int chunkZ) {
        // Guarded like the other two. Disabled, the map is always empty, and this is called once per unloaded column
        // of every capture - so without it the one thing a switched-off cache does is allocate keys to look nothing up.
        if (!enabled()) return;

        held.remove(new Key(world, chunkX, chunkZ));
    }

    /**
     * Drops everything past its lifetime.
     *
     * <p>Called at the top of a capture rather than on a timer, which keeps the whole class free of the server: the
     * cost is a walk of at most {@link #CAPACITY} entries once per capture, and it stops a camera that has been put
     * down from holding stale columns until the count bound happens to push them out.
     */
    synchronized void expire(long now) {
        Iterator<Map.Entry<Key, Held>> each = held.entrySet().iterator();
        while (each.hasNext()) {
            if (now - each.next().getValue().takenAt() > lifetimeNanos) {
                each.remove();
            }
        }
    }

    synchronized int size() {
        return held.size();
    }

    synchronized long hits() {
        return hits;
    }

    synchronized long lookups() {
        return lookups;
    }
}
