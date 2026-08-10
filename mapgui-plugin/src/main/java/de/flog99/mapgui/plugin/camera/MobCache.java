package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a mob <i>looks like</i>, kept between captures - never where it is standing, which is read fresh every time.
 *
 * <p>A stale position teleports a mob; a stale shape only means the sword it just drew appears a moment late. The
 * shape is also the whole cost: a part tree, nine equipment slots, its variant and its skin. Graded by
 * {@link ReuseWindow}, and live views only, since a still is never corrected by a frame that follows.
 */
final class MobCache {

    /** Shapes held at most, which covers a frame several times over. Least recently used past that. */
    static final int CAPACITY = 192;

    /**
     * One mob's shape, split by which angle each layer follows: nearly everything turns with the head, but an item
     * in a hand or a block in a minecart turns with the body and never leans. Recorded at build time rather than
     * guessed at, since the two look identical until the mob turns its head.
     */
    record Built(List<EntitySnapshot> withHead, List<EntitySnapshot> withBody) {

        Built(List<EntitySnapshot> withHead, List<EntitySnapshot> withBody) {
            this.withHead = List.copyOf(withHead);
            this.withBody = List.copyOf(withBody);
        }

        /** Everything drawn for this mob, at the pose it was built at. */
        List<EntitySnapshot> all() {
            List<EntitySnapshot> drawn = new ArrayList<>(withHead.size() + withBody.size());
            drawn.addAll(withHead);
            drawn.addAll(withBody);
            return drawn;
        }

        /** The same shape standing where the mob is now, which is all a reused capture recomputes. */
        List<EntitySnapshot> standing(double x, double y, double z, float bodyYaw, float headYaw, float pitch) {
            List<EntitySnapshot> drawn = new ArrayList<>(withHead.size() + withBody.size());
            for (EntitySnapshot layer : withHead) {
                drawn.add(layer.at(x, y, z, bodyYaw, headYaw, pitch));
            }
            for (EntitySnapshot layer : withBody) {
                drawn.add(layer.at(x, y, z, bodyYaw, bodyYaw, 0));
            }
            return drawn;
        }

        /** Turned bodily, for a mob standing on its head. A shape change, so it is cached with the shape. */
        Built tilted(float xRot, float zRot, float pivotY) {
            return new Built(tilted(withHead, xRot, zRot, pivotY), tilted(withBody, xRot, zRot, pivotY));
        }

        private static List<EntitySnapshot> tilted(List<EntitySnapshot> layers, float xRot, float zRot, float pivotY) {
            List<EntitySnapshot> turned = new ArrayList<>(layers.size());
            for (EntitySnapshot layer : layers) {
                turned.add(layer.tilted(xRot, zRot, pivotY));
            }
            return turned;
        }
    }

    private record Held(Built built, long takenAt) {
    }

    /** Access-ordered, so the eldest really is the one no capture has wanted for longest. */
    private final Map<UUID, Held> held = new LinkedHashMap<>(CAPACITY * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Held> eldest) {
            return size() > CAPACITY;
        }
    };

    private long hits;
    private long lookups;

    /** What a viewfinder frame may reuse a shape for, by how far off the mob is, in blocks. */
    private final ReuseWindow live;

    MobCache() {
        this(CameraTuning.Reuse.MOBS);
    }

    MobCache(ReuseWindow live) {
        this.live = live;
    }

    /** @param blocksAway from the eye the capture is taken at */
    long allowedAgeNanos(double blocksAway) {
        return live.allowedAgeNanos(blocksAway);
    }

    /**
     * @param now read once per capture, so every mob in one frame is judged against the same instant
     * @return null when this mob was never built, or was built too long ago to stand behind
     */
    synchronized Built get(UUID entity, long allowedAgeNanos, long now) {
        // Counted even on a miss, so the rate is a fraction of the mobs drawn rather than of the ones already held.
        lookups++;

        Held entry = held.get(entity);
        if (entry == null) return null;

        if (now - entry.takenAt() > allowedAgeNanos) {
            held.remove(entity);
            return null;
        }

        hits++;
        return entry.built();
    }

    synchronized void put(UUID entity, Built built, long now) {
        held.put(entity, new Held(built, now));
    }

    /**
     * Drops everything past its window, once per capture - which is also what releases mobs that have died, since
     * nothing asks for their id again and the count bound alone would take a while to reach them.
     */
    synchronized void expire(long now) {
        Iterator<Map.Entry<UUID, Held>> each = held.entrySet().iterator();

        while (each.hasNext()) {
            if (now - each.next().getValue().takenAt() > live.longestNanos()) {
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
