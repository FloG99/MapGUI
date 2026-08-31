package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * What color a box is at a given pixel.
 *
 * <p>A solid fill is the common case; a gradient is the reason this is a function rather than just a
 * {@link Color}.
 *
 * <p>A fill also answers for its own quantizing, which is the one thing here that is not obvious. See
 * {@link #dither()}: the painter does not decide whether a fill wants dithering, because the fill is the thing
 * that knows.
 */
public interface Fill {

    Color at(int x, int y, Rect bounds);

    /**
     * The {@link Dither} mode this fill asks to be drawn with, or null if it has no opinion and whatever the
     * painter is scoped to should decide.
     *
     * <p>Null and {@link Dither#NONE} are deliberately different answers. A fill with no opinion may be
     * dithered by a painter that was told to dither what it is drawing; {@link Dither#NONE} is a fill asking for
     * banding, and that has to survive being drawn inside such a scope.
     *
     * <p>Why the fill answers rather than the painter looking at it: a gradient is by definition asking for a
     * ramp the palette cannot express, and one drawn without dithering does not read as a stylistic choice, it
     * reads as broken. But dithering everything is equally wrong - it only adds noise to a flat button, and
     * {@code docs/performance.md} records that the pattern compresses poorly, so it would cost bandwidth on
     * every screen to help the few that ramp. The thing that knows which case it is, is the fill.
     */
    default Dither dither() {
        return null;
    }

    /**
     * The same fill, drawn with a different mode.
     *
     * <p>{@code Fill.gradient(from, to, VERTICAL).dither(Dither.NONE)} is banded on purpose, and stays banded
     * wherever it is drawn.
     */
    default Fill dither(Dither mode) {
        return new Quantized(this, mode);
    }

    enum Direction { HORIZONTAL, VERTICAL, DIAGONAL }

    static Fill solid(Color color) {
        return new Solid(color);
    }

    /** Dithered unless told otherwise, per {@link #dither()}. */
    static Fill gradient(Color from, Color to, Direction direction) {
        return new Gradient(from, to, direction, Dither.ORDERED);
    }

    private static double progress(int x, int y, Rect bounds, Direction direction) {
        double across = bounds.width() <= 1 ? 0 : (double) (x - bounds.x()) / (bounds.width() - 1);
        double down = bounds.height() <= 1 ? 0 : (double) (y - bounds.y()) / (bounds.height() - 1);

        return switch (direction) {
            case HORIZONTAL -> across;
            case VERTICAL -> down;
            case DIAGONAL -> (across + down) / 2;
        };
    }

    record Solid(Color color) implements Fill {

        @Override
        public Color at(int x, int y, Rect bounds) {
            return color;
        }
    }

    /** A ramp between two colors, carrying the mode it wants to be drawn with. */
    record Gradient(Color from, Color to, Direction direction, Dither dither) implements Fill {

        @Override
        public Color at(int x, int y, Rect bounds) {
            return Colors.mix(from, to, progress(x, y, bounds, direction));
        }

        @Override
        public Fill dither(Dither mode) {
            return new Gradient(from, to, direction, mode);
        }
    }

    /** Any other fill with a mode put on it - the ones this interface did not write itself. */
    record Quantized(Fill base, Dither dither) implements Fill {

        @Override
        public Color at(int x, int y, Rect bounds) {
            return base.at(x, y, bounds);
        }

        @Override
        public Fill dither(Dither mode) {
            return new Quantized(base, mode);
        }
    }
}
