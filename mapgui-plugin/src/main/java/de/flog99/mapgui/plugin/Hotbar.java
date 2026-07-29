package de.flog99.mapgui.plugin;

/** The mouse wheel, as the server sees it: a change of selected hotbar slot. */
final class Hotbar {

    static final int SLOTS = 9;

    private Hotbar() {
    }

    /**
     * How far the wheel turned, by the shorter way round.
     *
     * <p>The client wraps from slot 8 to slot 0, where a plain subtraction reads one notch forwards as
     * eight notches backwards - so scrolling off the end of the hotbar threw the menu the other way.
     */
    static int notches(int from, int to) {
        int delta = Math.floorMod(to - from, SLOTS);
        return delta > SLOTS / 2 ? delta - SLOTS : delta;
    }
}
