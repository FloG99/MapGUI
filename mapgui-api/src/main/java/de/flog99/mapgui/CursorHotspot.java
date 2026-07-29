package de.flog99.mapgui;

import org.bukkit.map.MapCursor;

import java.util.Map;

/**
 * Where each cursor icon's point actually is, relative to the middle of its art.
 *
 * <p>The client centers an icon on the coordinate it is given, but most do not point at their own middle - a
 * marker's tip is at the top of its art, so aiming it at a button put the real click half an icon lower.
 * Offsetting by what is listed here puts the point exactly where MapGUI is hit-testing, and does the same for
 * a {@link Marker}.
 */
public final class CursorHotspot {

    /** Icon space is twice as fine as map pixels, so one pixel is two of these. */
    private static final int PER_PIXEL = 2;

    /**
     * How far above the middle of the icon its point sits.
     *
     * <p>Only these have been checked in game. Anything absent is treated as pointing at its own middle,
     * which is right for the rotating arrows and for anything cross-shaped, and is only a few pixels out
     * otherwise.
     */
    private static final Map<MapCursor.Type, Integer> POINT_ABOVE_CENTRE = Map.of(
            // Tip at the very top of the art. Blue is the same shape in another color.
            MapCursor.Type.RED_MARKER, 5 * PER_PIXEL,
            MapCursor.Type.BLUE_MARKER, 5 * PER_PIXEL
    );

    private CursorHotspot() {
    }

    /** In icon-space units, so add it to a converted y to move the icon down onto its point. */
    public static int above(MapCursor.Type type) {
        return POINT_ABOVE_CENTRE.getOrDefault(type, 0);
    }
}
