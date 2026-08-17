package de.flog99.mapgui;

import org.bukkit.map.MapCursor;
import org.jetbrains.annotations.Nullable;

/**
 * An icon overlaid on the surface, drawn by the client rather than into the pixel buffer.
 *
 * <p>Coordinates are surface pixels, like everything else in the API; the conversion to the
 * map's own -128..127 cursor space happens in the transport.
 *
 * <p>They are fractional because that space is twice as fine as the pixels, so an icon can sit on a half pixel.
 * A position is snapped to those halves on the way in rather than at the transport, since that is the resolution
 * the client can actually draw - and since a marker only counts as moved once it lands somewhere new, which is
 * what stops an icon drifting a hundredth of a pixel from costing a packet.
 */
public record Marker(
        MapCursor.Type type,
        double x,
        double y,
        byte rotation,
        @Nullable String label) {

    public Marker {
        x = snap(x);
        y = snap(y);
    }

    public static Marker at(MapCursor.Type type, double x, double y) {
        return new Marker(type, x, y, (byte) 8, null);
    }

    public Marker rotation(int direction) {
        return new Marker(type, x, y, (byte) (direction & 0x0F), label);
    }

    public Marker label(String text) {
        return new Marker(type, x, y, rotation, text);
    }

    /** The pixel a marker is in, for anything asking which one that is rather than where inside it. */
    public int pixelX() {
        return (int) Math.floor(x);
    }

    public int pixelY() {
        return (int) Math.floor(y);
    }

    private static double snap(double value) {
        return Math.round(value * 2) / 2.0;
    }
}
