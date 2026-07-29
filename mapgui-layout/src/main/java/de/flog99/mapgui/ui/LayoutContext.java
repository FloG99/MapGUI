package de.flog99.mapgui.ui;

/** Everything a node needs during measure and arrange that isn't part of the tree itself. */
public record LayoutContext(TextFont font, Animator animator) {

    /** For one-off layout with no animation, such as a single rendered frame or a unit test. */
    public LayoutContext(TextFont font) {
        this(font, new Animator());
    }
}
