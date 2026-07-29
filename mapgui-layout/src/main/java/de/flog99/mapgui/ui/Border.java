package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * A box outline: either one color all round, or a bevel with a lit and a shaded side.
 *
 * <p>Vanilla Minecraft widgets are drawn as bevels, which is why {@link #raised} and {@link #sunken}
 * exist - they work the shades out from the fill color, so the common case needs no colors at all.
 */
public record Border(int width, Kind kind, Color primary, Color secondary) {

    public enum Kind { NONE, SOLID, BEVEL }

    private static final Border NONE = new Border(0, Kind.NONE, null, null);

    /** Marks a bevel whose colors come from the fill; swapped for {@code sunken}. */
    private static final Color DERIVE_RAISED = new Color(0, 0, 0, 1);
    private static final Color DERIVE_SUNKEN = new Color(0, 0, 0, 2);

    public static Border none() {
        return NONE;
    }

    public static Border solid(int width, Color color) {
        return new Border(width, Kind.SOLID, color, null);
    }

    public static Border bevel(int width, Color light, Color dark) {
        return new Border(width, Kind.BEVEL, light, dark);
    }

    /** Bevel lit from the top left, shades derived from the fill. */
    public static Border raised(int width) {
        return new Border(width, Kind.BEVEL, DERIVE_RAISED, DERIVE_RAISED);
    }

    /** The same, inverted, so the box reads as pressed in. */
    public static Border sunken(int width) {
        return new Border(width, Kind.BEVEL, DERIVE_SUNKEN, DERIVE_SUNKEN);
    }

    public boolean visible() {
        return kind != Kind.NONE && width > 0;
    }

    /** Replaces derived shades with real ones, now that the fill is known. */
    public Border resolve(Color fill) {
        if (primary != DERIVE_RAISED && primary != DERIVE_SUNKEN) return this;

        Color base = fill != null ? fill : new Color(120, 126, 140);
        Color light = Colors.scale(base, 1.75);
        Color dark = Colors.scale(base, 0.45);
        return primary == DERIVE_RAISED
                ? new Border(width, Kind.BEVEL, light, dark)
                : new Border(width, Kind.BEVEL, dark, light);
    }

    /** Same border with a different main color, for hover overrides. */
    public Border recoloured(Color color) {
        return kind == Kind.BEVEL ? new Border(width, kind, color, secondary) : new Border(width, kind, color, null);
    }
}
