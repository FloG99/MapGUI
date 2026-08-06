package de.flog99.mapgui.ui;

import java.awt.Color;

public final class Colors {

    private Colors() {
    }

    public static Color rgb(int packed) {
        return new Color(packed, false);
    }

    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Scales toward black, keeping alpha. Used for text shadows and pressed states. */
    public static Color scale(Color color, double factor) {
        return new Color(
                clamp(color.getRed() * factor),
                clamp(color.getGreen() * factor),
                clamp(color.getBlue() * factor),
                color.getAlpha()
        );
    }

    /**
     * What a drop shadow under text is drawn in: a quarter brightness, the way the game does it.
     *
     * <p>Not {@link #scale} at 0.25, close as that is. This truncates rather than rounds and comes back opaque,
     * so a shadow under translucent text is a shadow rather than another translucent layer - and the pixels it
     * produces are the ones text shadows have always been drawn with.
     */
    public static Color shadow(Color color) {
        return new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4);
    }

    public static Color mix(Color from, Color to, double amount) {
        double inverse = 1 - amount;
        return new Color(
                clamp(from.getRed() * inverse + to.getRed() * amount),
                clamp(from.getGreen() * inverse + to.getGreen() * amount),
                clamp(from.getBlue() * inverse + to.getBlue() * amount)
        );
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }
}
