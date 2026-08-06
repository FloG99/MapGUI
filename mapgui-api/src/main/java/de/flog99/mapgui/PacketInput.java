package de.flog99.mapgui;

import org.bukkit.entity.Player;

/**
 * Reads the two gestures that no event can see.
 *
 * <p>Both are invisible for the same reason: the server decides what happened from the item really in the player's
 * hand, and MapGUI's map is not in their inventory at all. <b>Drop</b> only becomes {@code PlayerDropItemEvent} once
 * there is an item entity to hand, and <b>right-click into air</b> is worse - the server's whole handler sits behind
 * {@code if (!itemStack.isEmpty())}, so it fired or not depending on what the player happened to be carrying.
 *
 * <p>Right-clicking a block or an entity does raise events, but they are read here too so every right-click arrives
 * by one route whatever the player is aiming at.
 */
public interface PacketInput {

    void listen(Player player, Handler handler);

    void forget(Player player);

    /**
     * All of these run on the network thread - hop before touching anything.
     *
     * <p>Each returns whether it took the gesture. False passes the packet on untouched, which is what lets
     * a listener stay installed on a player pointing at nothing of ours: swallowing unconditionally would
     * mean they could never right-click a door again.
     *
     * <p>That decision can therefore only read state that is safe to read from a network thread. A tick out
     * of date is fine for "is this player using one of our menus".
     */
    interface Handler {

        boolean drop();

        boolean rightClick();

        /**
         * Right-click into empty air, as against at a block or an entity.
         *
         * <p>Its own method because one focus mode turns on the difference: a map that is focused by right-clicking
         * has to leave a door openable, so it takes the click that hit nothing and lets the rest past. Everything
         * else wants all three, which is what the default gives.
         */
        default boolean rightClickAir() {
            return rightClick();
        }

        /**
         * Left-click while aiming at an entity, which is less niche than it sounds: every frame MapGUI puts
         * up is a client-only entity, so the client raytraces one of those first and reports an attack rather
         * than a block click. The server has no such entity and drops the packet, so no event is raised.
         *
         * <p>Ignored by default: a menu must not be usable to hit things.
         */
        default boolean leftClick() {
            return false;
        }
    }
}
