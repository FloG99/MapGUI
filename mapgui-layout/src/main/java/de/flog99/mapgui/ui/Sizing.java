package de.flog99.mapgui.ui;

/**
 * How a node decides its size along one axis, and the bounds that size is held to.
 *
 * <p>{@code HUG} shrink-wraps the content, {@code FIXED} is an exact pixel count and
 * {@code FILL} claims a share of whatever space is left, split between siblings by weight.
 *
 * <p>The bounds sit on top of whichever mode is in use, because the three modes alone cannot say
 * "as wide as the content but never past 80". They live here rather than on the node so that a
 * container can read them off a plain {@link Node} - {@link Panel} has to, since a capped
 * {@code FILL} child hands its surplus back to its siblings.
 */
public record Sizing(Mode mode, int value, int min, int max) {

    public enum Mode { HUG, FIXED, FILL }

    /** What an unset maximum is. Larger than any surface, and still safe to compare and divide with. */
    public static final int NO_MAX = Integer.MAX_VALUE;

    private static final Sizing HUG = new Sizing(Mode.HUG, 0, 0, NO_MAX);

    public Sizing {
        value = Math.max(0, value);
        min = Math.max(0, min);
        max = Math.max(0, max);
    }

    public static Sizing hug() {
        return HUG;
    }

    public static Sizing fixed(int pixels) {
        return new Sizing(Mode.FIXED, pixels, 0, NO_MAX);
    }

    public static Sizing fill(int weight) {
        return new Sizing(Mode.FILL, Math.max(1, weight), 0, NO_MAX);
    }

    /** The same mode and value, held between two bounds. */
    public Sizing bounded(int minimum, int maximum) {
        return new Sizing(mode, value, minimum, maximum);
    }

    public Sizing withMin(int pixels) {
        return bounded(pixels, max);
    }

    public Sizing withMax(int pixels) {
        return bounded(min, pixels);
    }

    /**
     * Holds a resolved size to the bounds.
     *
     * <p>The maximum applies first and the minimum second, so a maximum beats an exact size -
     * {@code width(200).maxWidth(120)} resolves to 120 rather than one of the two being quietly dropped -
     * and a contradictory pair resolves to the minimum.
     */
    public int clamp(int pixels) {
        return Math.max(min, Math.min(max, pixels));
    }

    public boolean isFill() {
        return mode == Mode.FILL;
    }
}
