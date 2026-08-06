package de.flog99.mapgui;

/**
 * Which of a player's own slots a virtual map is shown in.
 *
 * <p>Every one of these is a lie told to one client. Nothing here touches the inventory the server holds, so a
 * slot showing a map may really hold a sword, and the sword is still there the moment the pretence stops.
 *
 * <p>Hotbar slots are the player's own 0 to 8, not inventory indices - the numbers on the keys.
 */
public record MapSlots(int hotbarMask, Offhand offhand) {

    /** What the offhand is told, which is a question of its own because an empty one changes how a map is drawn. */
    public enum Offhand {

        /** The map is in the offhand. */
        MAP,

        /**
         * Reported empty whatever is really there.
         *
         * <p>Not cosmetic: the client draws a held map large and two-handed only while the other hand is free, and
         * anything in it shrinks the map into a corner. A popup wants the whole screen, so it claims both hands.
         */
        EMPTY,

        /** Left alone, so the player sees what they are really carrying. */
        REAL
    }

    /** All nine hotbar slots. */
    public static final int ALL_HOTBAR = 0b1_1111_1111;

    /** Every hotbar slot, and both hands claimed - a popup. */
    public static MapSlots wholeHotbar() {
        return new MapSlots(ALL_HOTBAR, Offhand.EMPTY);
    }

    /** One hotbar slot, with the offhand left to the player. */
    public static MapSlots hotbar(int slot) {
        return new MapSlots(1 << slot, Offhand.REAL);
    }

    /** The offhand alone, with the whole hotbar left to the player. */
    public static MapSlots offhandOnly() {
        return new MapSlots(0, Offhand.MAP);
    }

    /** Nothing faked at all, for a map that is a real item and needs no pretending about. */
    public static MapSlots none() {
        return new MapSlots(0, Offhand.REAL);
    }

    public boolean shows(int hotbarSlot) {
        return (hotbarMask & 1 << hotbarSlot) != 0;
    }

    /** Whether anything at all is being faked, so a caller can skip installing the pretence. */
    public boolean any() {
        return hotbarMask != 0 || offhand != Offhand.REAL;
    }
}
