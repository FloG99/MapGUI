package de.flog99.mapgui.ui;

/** Main-axis distribution of children inside a container. */
public enum Justify {
    START,
    CENTER,
    END,
    /** Leftover space split between the children, with none at the edges. */
    SPACE_BETWEEN,
    /** Every child gets the same space on each of its sides, so an edge gap is half an inner one. */
    SPACE_AROUND,
    /** The same space everywhere, edges included, so an edge gap equals an inner one. */
    SPACE_EVENLY;

    /**
     * Where the first child starts, given the leftover space and how many children share it.
     *
     * <p>Lives here rather than in each container so that a {@link Flow} line and a {@link Panel} row cannot
     * drift apart on what a constant means. {@code count} is at least one.
     */
    int offset(int free, int count) {
        return switch (this) {
            case START, SPACE_BETWEEN -> 0;
            case CENTER -> free / 2;
            case END -> free;
            case SPACE_AROUND -> free / (2 * count);
            case SPACE_EVENLY -> free / (count + 1);
        };
    }

    /** How much is added to the gap between two neighbouring children. */
    int extraGap(int free, int count) {
        return switch (this) {
            case SPACE_BETWEEN -> count > 1 ? free / (count - 1) : 0;
            case SPACE_AROUND -> free / count;
            case SPACE_EVENLY -> free / (count + 1);
            default -> 0;
        };
    }
}
