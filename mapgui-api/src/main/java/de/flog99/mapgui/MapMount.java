package de.flog99.mapgui;

import org.bukkit.entity.Player;

/**
 * Whatever is holding a set of maps up, so pixels have somewhere to appear.
 *
 * <p>Built once and shown to as many players as you like. Nothing is placed in the world: no entity to
 * break, no map item inside it, nothing left behind by a restart or a crash mid-cleanup.
 *
 * <p>Showing is not one-and-done. Clients throw away entities whose chunk unloads, so anyone who walks out
 * of range and back - or reconnects, respawns, changes world - is shown them again. That is the same path a
 * player joining an hour later takes, which is what makes late joiners work.
 */
public interface MapMount {

    void show(Player player);

    void hide(Player player);

    /**
     * Whether this mount can be repointed at all, which is a property of the mount and not of any viewer.
     *
     * <p>Asked once, before anything is prerendered, so a transport that cannot do it costs nothing rather
     * than being found out a frame later with 32 painted surfaces already in hand.
     */
    default boolean repoints() {
        return false;
    }

    /**
     * Points the maps at different ids for one viewer, without sending a single pixel.
     *
     * <p>Which is a whole frame of animation for the price of a few bytes, as long as the client has already
     * been sent the pixels under those ids. A short loop can therefore be sent once and then played by
     * flipping between the copies - see {@link WallDisplay.Builder#prerender}.
     *
     * <p>Only called when {@link #repoints} is true.
     *
     * @param mapIds one per map, in the same order as the mount was built
     */
    default void showMaps(Player player, int[] mapIds) {
    }
}
