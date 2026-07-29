package de.flog99.mapgui;

import org.bukkit.map.MapCursor;
import org.jetbrains.annotations.Nullable;

/**
 * An icon overlaid on the surface, drawn by the client rather than into the pixel buffer.
 *
 * <p>Coordinates are surface pixels, like everything else in the API; the conversion to the
 * map's own -128..127 cursor space happens in the transport.
 */
public record Marker(
        MapCursor.Type type,
        int x,
        int y,
        byte rotation,
        @Nullable String label) {

    public static Marker at(MapCursor.Type type, int x, int y) {
        return new Marker(type, x, y, (byte) 8, null);
    }

    public Marker rotation(int direction) {
        return new Marker(type, x, y, (byte) (direction & 0x0F), label);
    }

    public Marker label(String text) {
        return new Marker(type, x, y, rotation, text);
    }
}
