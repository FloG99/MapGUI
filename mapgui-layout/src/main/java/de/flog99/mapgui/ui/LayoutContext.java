package de.flog99.mapgui.ui;

/** Everything a node needs during measure and arrange that isn't part of the tree itself. */
public record LayoutContext(TextFont font, Animator animator) {

    /** For one-off layout with no animation, such as a single rendered frame or a unit test. */
    public LayoutContext(TextFont font) {
        this(font, new Animator());
    }

    /**
     * The same context measuring in another font, for one subtree.
     *
     * <p>A new context rather than a setting on this one, because the measure pass walks down and back up: a
     * node hands its children a context and the one it was given has to be untouched when they are done. Which
     * is the same thing {@link Painter#pushFont} does for the paint pass, spelled the way a record can be.
     *
     * <p>Null and the font already in use both give this context back, so nothing allocates for the common case
     * of a subtree that has no font of its own.
     */
    @org.jetbrains.annotations.ApiStatus.Experimental
    public LayoutContext withFont(TextFont value) {
        return value == null || value == font ? this : new LayoutContext(value, animator);
    }
}
