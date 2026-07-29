package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * A named palette, so screens stop inventing their own colors.
 *
 * <p>The point of routing colors through here rather than hardcoding them per screen is that a
 * server can restyle every plugin built on MapGUI at once.
 */
public record Theme(
        Color background,
        Color surface,
        Color surfaceHigh,
        Color accent,
        Color accentHigh,
        Color text,
        Color muted,
        Color outline,
        Color success,
        Color warning,
        Color danger) {

    public static final Theme DARK = new Theme(
            new Color(18, 20, 27),
            new Color(34, 38, 50),
            new Color(48, 53, 68),
            new Color(88, 116, 232),
            new Color(132, 158, 255),
            new Color(238, 240, 245),
            new Color(140, 148, 165),
            new Color(72, 79, 96),
            new Color(56, 193, 96),
            new Color(240, 176, 64),
            new Color(206, 51, 51)
    );

    public static final Theme LIGHT = new Theme(
            new Color(232, 234, 240),
            new Color(210, 214, 224),
            new Color(224, 228, 238),
            new Color(52, 88, 216),
            new Color(96, 132, 248),
            new Color(24, 26, 34),
            new Color(96, 104, 122),
            new Color(160, 167, 182),
            new Color(30, 148, 72),
            new Color(184, 120, 16),
            new Color(178, 34, 34)
    );

    public Theme withAccent(Color value) {
        return new Theme(background, surface, surfaceHigh, value, Colors.scale(value, 1.35),
                text, muted, outline, success, warning, danger
        );
    }

    public Theme withBackground(Color value) {
        return new Theme(value, surface, surfaceHigh, accent, accentHigh, text, muted, outline, success, warning, danger);
    }
}
