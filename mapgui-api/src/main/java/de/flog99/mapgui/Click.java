package de.flog99.mapgui;

/**
 * Which mouse button presses things.
 *
 * <p>{@link #RIGHT} by default, and worth keeping: left-click plays the arm swing, so the map visibly drops
 * and springs back on every press. The client starts that before the server hears the click, so nothing can
 * suppress it.
 */
public enum Click {

    /** The default, and the only one with no arm swing. */
    RIGHT,

    /** Left only, arm swing and all. */
    LEFT,

    /** Either button, for menus where being picky would only annoy people. */
    BOTH;

    public boolean accepts(Click button) {
        return this == BOTH || this == button;
    }
}
