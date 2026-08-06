package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * One frame's worth of extracted map pixels, so a wall with an audience copies each map once.
 *
 * <p>A shared wall paints one surface and sends it to everybody, but the sending is per viewer - a bundle
 * cannot be open for two clients at once, so the frame is walked once per player. Without this, a nine map
 * wall in front of twenty people copies 147 KB out of the surface twenty times a frame to hand each client
 * the same bytes.
 *
 * <p>Kept for a single tick and thrown away, because the next frame's pixels are different ones. Keyed by the
 * surface as well as the map, so a per-player wall - where every viewer has a surface of their own - shares
 * nothing, which is the correct answer rather than a missed optimization.
 *
 * <p>What comes out is never written to, which is what makes handing the same array to several packets safe.
 */
final class TileRegions {

    private record Key(MapSurface surface, int tile, Rect area) {
    }

    private final Map<Key, byte[]> extracted = new HashMap<>();

    byte[] of(MapSurface surface, int tile, Rect area) {
        return extracted.computeIfAbsent(new Key(surface, tile, area), key -> key.surface().region(key.area()));
    }
}
