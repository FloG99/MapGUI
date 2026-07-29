package de.flog99.mapgui.ui;

/**
 * How a node decides its size along one axis.
 *
 * <p>{@code HUG} shrink-wraps the content, {@code FIXED} is an exact pixel count and
 * {@code FILL} claims a share of whatever space is left, split between siblings by weight.
 */
public record Sizing(Mode mode, int value) {

    public enum Mode { HUG, FIXED, FILL }

    private static final Sizing HUG = new Sizing(Mode.HUG, 0);

    public static Sizing hug() {
        return HUG;
    }

    public static Sizing fixed(int pixels) {
        return new Sizing(Mode.FIXED, Math.max(0, pixels));
    }

    public static Sizing fill(int weight) {
        return new Sizing(Mode.FILL, Math.max(1, weight));
    }

    public boolean isFill() {
        return mode == Mode.FILL;
    }
}
