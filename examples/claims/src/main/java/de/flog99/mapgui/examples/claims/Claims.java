package de.flog99.mapgui.examples.claims;

import de.flog99.mapgui.SharedModel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Which team owns which chunk.
 *
 * <p>A chunk holds a {@link Team} and nothing else - no owner, no timestamp. The color on the map is the
 * whole truth, which is what makes it readable at a glance and the rules easy to say out loud: you can take
 * unclaimed ground, and you can give back ground your own team took.
 *
 * <p>In memory and per world only, because this is an example of a menu rather than of a claim plugin. A
 * real one would persist this and care about worlds.
 *
 * <p>A {@link SharedModel}, so a claim taken by one player appears on every other open map at once rather
 * than the next time that player happens to make their own map redraw.
 */
final class Claims extends SharedModel {

    private final Map<Long, Team> owners = new HashMap<>();

    /** Both halves packed into one key, so no object is allocated per lookup while painting. */
    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    @Nullable
    Team at(int chunkX, int chunkZ) {
        return owners.get(key(chunkX, chunkZ));
    }

    void claim(int chunkX, int chunkZ, Team team) {
        owners.put(key(chunkX, chunkZ), team);
        changed();
    }

    void release(int chunkX, int chunkZ) {
        owners.remove(key(chunkX, chunkZ));
        changed();
    }
}
