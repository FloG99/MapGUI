package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The drawn block entities of a column, kept between captures for as long as a copied column is.
 *
 * <p>Because that is what they are. A chest, a sign, a banner, a bell: they do not move, and what they look like
 * changes when somebody edits a sign or puts something on a shelf rather than every tick. A mob is the opposite and
 * is never cached - a stale mob is drawn where it is not - but a stale chest is very nearly always the same chest.
 *
 * <p>Graded by distance like everything else the camera holds - see {@link ReuseWindow} - but in blocks rather than
 * chunks, because the whole gather is capped at 64 blocks and a chunk-wide ramp would have barely begun by then.
 *
 * <p>Nothing that animates is on this path, which is what makes half a second safe. An item frame - a map filling
 * in, or one of MapGUI's own walls playing - is an <i>entity</i>, drawn by {@code EntityCapture} against
 * {@code FramedMaps}, and the entity path holds nothing between captures at all.
 *
 * <p>Held per column and unfiltered, rather than per capture and filtered by distance from the eye. A photographer
 * takes a step between frames, so a list built against where they were standing would be subtly wrong for where
 * they are now - and a cache that has to be rebuilt whenever the camera moves is not a cache. The distance test is
 * applied to what comes back instead, which is arithmetic on a snapshot that already exists.
 */
final class BlockEntityCache {

    /**
     * Columns held at most.
     *
     * <p>A capture looks at 81 of them before culling, so this covers one and a few steps of walking. Least
     * recently used past that, the same as the column cache.
     */
    static final int CAPACITY = 192;

    private record Key(UUID world, int chunkX, int chunkZ) {
    }

    private record Held(List<EntitySnapshot> drawn, long takenAt) {
    }

    /** Access-ordered, so the eldest really is the one no capture has wanted for longest. */
    private final Map<Key, Held> held = new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Held> eldest) {
            return size() > CAPACITY;
        }
    };

    /** Only for how long a column may be trusted by a <b>still</b>, which is a decision a server already made. */
    private final SnapshotCache ages;

    /** And what a viewfinder frame may reuse one for, by how far off the column is. */
    private final ReuseWindow live;

    BlockEntityCache(SnapshotCache ages) {
        this(ages, CameraTuning.Reuse.BLOCK_ENTITIES);
    }

    BlockEntityCache(SnapshotCache ages, ReuseWindow live) {
        this.ages = ages;
        this.live = live;
    }

    /**
     * @param blocksAway how far the nearest corner of this column is from the eye, which is what grades it
     * @return null when this column was never drawn, or was drawn too long ago to stand behind
     */
    synchronized List<EntitySnapshot> get(UUID world, int chunkX, int chunkZ, long now, boolean viewfinder, double blocksAway) {
        long allowed = viewfinder ? live.allowedAgeNanos(blocksAway) : ages.allowedAgeNanos(false, 0);
        if (allowed <= 0) return null;

        Key key = new Key(world, chunkX, chunkZ);
        Held entry = held.get(key);
        if (entry == null) return null;

        if (now - entry.takenAt() > allowed) {
            held.remove(key);
            return null;
        }
        return entry.drawn();
    }

    synchronized void put(UUID world, int chunkX, int chunkZ, List<EntitySnapshot> drawn, long now) {
        held.put(new Key(world, chunkX, chunkZ), new Held(List.copyOf(drawn), now));
    }

    /** For a column that has unloaded: what comes back under that name later was not what was drawn. */
    synchronized void forget(UUID world, int chunkX, int chunkZ) {
        held.remove(new Key(world, chunkX, chunkZ));
    }

    /** Drops everything past the longest a column may be trusted for, once per capture. */
    synchronized void expire(long now) {
        long longest = Math.max(live.longestNanos(), ages.allowedAgeNanos(false, 0));
        Iterator<Map.Entry<Key, Held>> each = held.entrySet().iterator();

        while (each.hasNext()) {
            if (now - each.next().getValue().takenAt() > longest) {
                each.remove();
            }
        }
    }

    synchronized int size() {
        return held.size();
    }
}
