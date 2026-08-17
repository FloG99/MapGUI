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
     * <p>Each gesture that can be claimed returns whether it took it. False passes the packet on untouched, which is what lets
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

        /**
         * The right button being let go, and the only thing a client ever says about one.
         *
         * <p>Told rather than offered, which is why this alone returns nothing: the packet is passed on whoever
         * hears it, and every claim hears it. Swallowing would be taking something that was never a click - on a
         * server that is not using an item it does nothing at all, and on one that is it belongs to whatever
         * started that, not to a menu.
         *
         * <p>Only sent by a client that thinks it is using an item, which is what {@link HandRaiser} is for: a
         * {@link Screen#holdable()} screen has the player's hand raised on the press, and this is the client
         * answering. Nothing on the item decides it, and a player whose hand was never raised is held down in
         * silence.
         */
        default void useReleased() {
        }
    }
}
