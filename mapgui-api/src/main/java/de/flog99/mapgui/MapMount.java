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
}
