package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * What color a box is at a given pixel.
 *
 * <p>A solid fill is the common case; a gradient is the reason this is a function rather than just a
 * {@link Color}. Non-solid fills are dithered when painted, since the map palette has too few stops
 * to ramp between arbitrary colors without banding.
 */
public interface Fill {

    Color at(int x, int y, Rect bounds);

    /** Whether every pixel is the same color, which is what decides if dithering is worth it. */
    default boolean uniform() {
        return false;
    }

    enum Direction { HORIZONTAL, VERTICAL, DIAGONAL }

    static Fill solid(Color color) {
        return new Solid(color);
    }

    static Fill gradient(Color from, Color to, Direction direction) {
        return (x, y, bounds) -> Colors.mix(from, to, progress(x, y, bounds, direction));
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

        @Override
        public boolean uniform() {
            return true;
        }
    }
}
