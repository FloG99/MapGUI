package de.flog99.mapgui.ui;

/**
 * How a box treats its corners.
 *
 * <p>Beyond {@code ROUND}, these are pixel-art shapes that CSS can only fake with a clip path, and
 * that cost nothing on a pixel grid.
 */
public enum Corner {
    /** No treatment. */
    SQUARE,
    /** Quarter circle. */
    ROUND,
    /** Straight 45 degree cut. */
    BEVEL,
    /** Square bite taken out of the corner. */
    NOTCH,
    /** Chunky staircase, two pixels per tread. */
    STEP
}
