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
    BOTH,

    /**
     * Neither, and the click is not taken off the player at all.
     *
     * <p>The other three all <b>swallow</b> a click aimed at the wall, whichever button it was - a menu must not also
     * mine the block it hangs on. This one says the screen wants nothing right now, so the click goes to the world
     * instead: a viewer can shoot a bow through it, hit what is behind it, and place against it.
     *
     * <p>For a screen that is a picture rather than a menu <i>at this moment</i>, and it is worth being dynamic
     * about. A portal is the case it was added for: it takes both buttons from somebody holding a portal gun, since
     * a shot aimed at one raises no event of its own, and nothing at all from anybody else - who would otherwise
     * find their bow silently dead whenever they looked at one.
     */
    NONE;

    /**
     * Whether a screen set to this answers to {@code button}.
     *
     * <p>{@link #NONE} is an answer about buttons rather than one of them, so it matches on neither side: a screen
     * set to it takes nothing, and it is not a button anything can be pressed with. Without the second half,
     * {@code BOTH.accepts(NONE)} would say yes on the way past.
     */
    public boolean accepts(Click button) {
        if (this == NONE || button == NONE) return false;

        return this == BOTH || this == button;
    }
}
