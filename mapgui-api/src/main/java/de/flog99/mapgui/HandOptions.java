package de.flog99.mapgui;

import org.bukkit.inventory.EquipmentSlot;

/**
 * How a screen is carried: what the player appears to hold, where it sits, and when it takes their mouse.
 *
 * <p><b>What the player is holding</b> is {@link Carry}. A popup fills the hotbar and cannot be moved, which is what
 * a menu wants; the other three put the map in one slot so the player keeps the rest of their hotbar. Of those,
 * {@link Carry#PINNED} and {@link Carry#OFFHAND} are a lie told to one client and nothing can take them, while
 * {@link Carry#ITEM} is a genuine {@code ItemStack}.
 *
 * <p><b>Whether it has the mouse</b> is {@link Focus}. A focused map draws a cursor, moves it with the player's head
 * and eats their clicks; an unfocused one still repaints but the clicks reach the world. A popup is always focused,
 * everything else is focused in the main hand and follows {@code focus} in the offhand, and {@link Focus#NEVER}
 * refuses both.
 *
 * <p>Defaults live in MapGUI's config so a server owner can change them for every GUI at once. A screen that cares
 * overrides {@link Screen#hand()}.
 *
 * @param slot           the hotbar slot the map sits in, 0 to 8, for {@link Carry#PINNED}. Ignored by the rest: a
 *                       popup is in all nine, an offhand map is in none, and a real item goes into the player's hand
 *                       and thereafter wherever they put it
 * @param movable        whether the player may move the map to another slot of their own. Never out of their own
 *                       inventory - that is what {@link Carry#ITEM} is for. Ignored for a popup
 * @param offhandAllowed whether the player may carry it in the offhand, where {@code focus} then decides whether
 *                       it is usable. Ignored for a popup, which refuses the offhand, and for
 *                       {@link Carry#OFFHAND}, which is nowhere else
 * @param mapId          the map id to draw this screen under, or 0 for one nobody else will get. Pin one to give a
 *                       resource pack something to recognise - see {@link #mapId(int)}
 */
public record HandOptions(Carry carry, Focus focus, int slot, boolean movable, boolean offhandAllowed, int mapId) {

    /** What the player is holding, and whether anything can take it from them. */
    public enum Carry {

        /**
         * A fake map in every hotbar slot, and the offhand reported empty so it draws large and two-handed.
         *
         * <p>MapGUI's original and its default. Nothing can move it, drop it or steal it, because it is not in
         * anybody's inventory - and every slot showing the same thing is what frees the scroll wheel to be the
         * menu's own scroll. A dialog rather than an item: while it is up, the player is in it.
         */
        POPUP,

        /**
         * A real {@code ItemStack} in the inventory, which behaves like one: picked up, dropped, handed over,
         * left in a chest.
         *
         * <p>The item is a key rather than a screen. Whoever holds it gets <b>their own</b> screen, built fresh
         * for them - so a phone found in a chest shows its finder their phone, and a television remote handed to
         * a friend works for the friend. Mint one with {@link MapGui#item(String, HandOptions)}, which binds it
         * to a GUI registered in the {@link GuiCatalog}; that name is what lets a later holder be given a screen
         * at all.
         *
         * <p>The one MapGUI cannot defend: a real item can be destroyed, duplicated by an admin, or lost in lava
         * like any other. It also costs nothing, since a map id is invented rather than allocated.
         */
        ITEM,

        /**
         * A fake map in one slot of the player's own inventory, which never leaves it.
         *
         * <p>Reads like an item and cannot be lost like one. The player sees it in their hotbar, can select it
         * and put it away by scrolling off it, and - if {@code movable} allows - drag it to another slot. Nothing
         * else can reach it: not a chest, not the ground, not another player.
         */
        PINNED,

        /**
         * A fake map in the offhand, with the main hand left entirely to the player.
         *
         * <p>For something worn rather than held: a quest log, a scoreboard, a bingo card you glance at while
         * fighting. It cannot be moved to the main hand, so {@code focus} is the only way to reach it - and
         * {@link Focus#SWAP_HANDS} is the natural one, since the key that would have swapped the hands has
         * nothing else to do.
         */
        OFFHAND
    }

    /**
     * What gives an offhand map the cursor and the player's clicks.
     *
     * <p>Only ever asked about the offhand. A map in the main hand is focused because that is what holding
     * something means, and a popup is focused because it is a popup.
     */
    public enum Focus {

        /**
         * Only the main hand. In the offhand it is a picture and nothing else.
         *
         * <p>The default, and the one that needs no explaining to a player: to use the map, hold it.
         */
        MAIN_HAND,

        /** Swapping hands - F, unless the player has rebound it - toggles focus, without moving anything. */
        SWAP_HANDS,

        /** Right-clicking into air toggles focus, so the same gesture puts the map down and picks it up. */
        RIGHT_CLICK,

        /** Focused while sneak is held, and let go the moment it is released. */
        SNEAK,

        /**
         * Focused in either hand.
         *
         * <p>Which means an offhand map eats every click the player makes, so they cannot mine, hit or place
         * anything until the screen closes. Worth it for something modal that still wants the main hand to show
         * a real item.
         */
        ALWAYS,

        /**
         * Never focused, in either hand. A display, with no cursor and no clicks at all.
         *
         * <p>For a screen that is only ever looked at, and the one value that overrules holding it: the player
         * carries it about and keeps every one of their own controls. A screen that needs to be pressed
         * occasionally wants {@link #MAIN_HAND} instead, which gives the mouse back the moment they scroll away.
         */
        NEVER
    }

    /** No pinned id, which is what almost every screen wants: one nobody else is drawing to. */
    public static final int ANY_MAP_ID = 0;

    /** The whole hotbar, unmovable, always focused - what MapGUI has always done. */
    public static HandOptions popup() {
        return new HandOptions(Carry.POPUP, Focus.MAIN_HAND, 0, false, false, ANY_MAP_ID);
    }

    /** A real item, in the first free slot, usable in either hand and a picture in the offhand. */
    public static HandOptions item() {
        return new HandOptions(Carry.ITEM, Focus.MAIN_HAND, -1, true, true, ANY_MAP_ID);
    }

    /** A fake item locked to one hotbar slot, 0 to 8, and not allowed in the offhand until you say so. */
    public static HandOptions pinned(int slot) {
        return new HandOptions(Carry.PINNED, Focus.MAIN_HAND, slot, false, false, ANY_MAP_ID);
    }

    /** A fake item in the offhand, reached by swapping hands since it cannot be held in the other one. */
    public static HandOptions offhand() {
        return new HandOptions(Carry.OFFHAND, Focus.SWAP_HANDS, -1, false, true, ANY_MAP_ID);
    }

    public HandOptions focus(Focus value) {
        return new HandOptions(carry, value, slot, movable, offhandAllowed, mapId);
    }

    public HandOptions slot(int value) {
        return new HandOptions(carry, focus, value, movable, offhandAllowed, mapId);
    }

    public HandOptions movable(boolean value) {
        return new HandOptions(carry, focus, slot, value, offhandAllowed, mapId);
    }

    public HandOptions allowOffhand(boolean value) {
        return new HandOptions(carry, focus, slot, movable, value, mapId);
    }

    /**
     * Draws this screen under a map id you choose, rather than one invented per session.
     *
     * <p>For a resource pack: the client renders a filled map from its {@code map_id} and reads nothing else, so an
     * id is the only handle a pack has to give one screen a model of its own. Take from the {@link MapIds#RESERVED}
     * at the top of the range, {@code Integer.MAX_VALUE - 1} downwards, which MapGUI never hands out - and from a
     * number of your own if other plugins might pin too, since nothing polices which you take.
     */
    public HandOptions mapId(int value) {
        return new HandOptions(carry, focus, slot, movable, offhandAllowed, value);
    }

    /** Whether the map only exists on the client, and so cannot be taken, dropped or lost. */
    public boolean faked() {
        return carry != Carry.ITEM;
    }

    /** Whether every hotbar slot shows the map, which is also what frees the wheel to be the menu's scroll. */
    public boolean fillsHotbar() {
        return carry == Carry.POPUP;
    }

    /** Whether the map can be in the offhand at all, either because it lives there or because it is allowed. */
    public boolean reachesOffhand() {
        return carry == Carry.OFFHAND || (offhandAllowed && carry != Carry.POPUP);
    }

    /** Whether the map can be in the main hand at all. False only for {@link Carry#OFFHAND}. */
    public boolean reachesMainHand() {
        return carry != Carry.OFFHAND;
    }

    /**
     * Whether a map held in {@code hand} has the cursor and the player's clicks right now.
     *
     * <p>The whole focus rule, in one place, so nothing has to work it out twice.
     *
     * @param sneaking whether the player is holding sneak, for {@link Focus#SNEAK}
     * @param toggled  whether a toggling gesture has been used since the map was last let go, for
     *                 {@link Focus#SWAP_HANDS} and {@link Focus#RIGHT_CLICK}
     */
    public boolean focused(EquipmentSlot hand, boolean sneaking, boolean toggled) {
        if (carry == Carry.POPUP) return true;
        if (focus == Focus.NEVER) return false;
        if (hand == EquipmentSlot.HAND) return true;

        return switch (focus) {
            case ALWAYS -> true;
            case SNEAK -> sneaking;
            case SWAP_HANDS, RIGHT_CLICK -> toggled;
            case MAIN_HAND, NEVER -> false;
        };
    }

    /** Whether a gesture is what focuses this, rather than simply holding it. Nothing to hint at otherwise. */
    public boolean togglesFocus() {
        return carry != Carry.POPUP && (focus == Focus.SWAP_HANDS || focus == Focus.RIGHT_CLICK);
    }

    /** Clamped and made consistent, so a hand-written config or a typo cannot produce a mode that makes no sense. */
    public HandOptions sane() {
        int wanted = carry == Carry.PINNED ? Math.clamp(slot, 0, 8) : slot;
        boolean offhand = carry == Carry.OFFHAND || (carry != Carry.POPUP && offhandAllowed);
        // A real map id is one the server could have allocated, so a pinned one under 1 is dropped rather than
        // trusted: it would paint over whatever map somebody already owns with that number.
        return new HandOptions(carry, focus, wanted, carry != Carry.POPUP && movable, offhand, Math.max(ANY_MAP_ID, mapId));
    }
}
