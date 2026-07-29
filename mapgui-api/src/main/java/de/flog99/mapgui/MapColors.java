package de.flog99.mapgui;

import de.flog99.mapgui.ui.Palette;
import org.bukkit.map.MapPalette;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Bukkit's map palette with a lookup cache.
 *
 * <p>{@code MapPalette.matchColor} walks the whole palette on every call, which is brutal
 * per-pixel; menus only use a handful of colors, so results are memoized. Everything runs on
 * the main thread, so a plain map is fine.
 */
@SuppressWarnings("removal") // Color matching is deprecated for removal with no replacement offered.
public final class MapColors implements Palette {

    public static final MapColors INSTANCE = new MapColors();

    private static final Color UNDEFINED = new Color(0, 0, 0);

    private final Map<Integer, Byte> toIndex = new HashMap<>();
    private final Color[] toColor = new Color[256];
    private byte[] entries;

    private MapColors() {
    }

    @Override
    public byte index(Color color) {
        Byte cached = toIndex.get(color.getRGB());
        if (cached != null) return cached;

        byte match = MapPalette.matchColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
        toIndex.put(color.getRGB(), match);
        return match;
    }

    /**
     * The palette doesn't define every one of the 256 indices, and Bukkit throws for the ones it
     * doesn't. Undefined slots come back transparent-black rather than taking the caller down.
     */
    @Override
    public Color color(byte index) {
        int slot = index & 0xFF;
        Color cached = toColor[slot];
        if (cached == null) {
            try {
                cached = MapPalette.getColor(index);
            } catch (RuntimeException e) {
                cached = UNDEFINED;
            }
            toColor[slot] = cached;
        }
        return cached;
    }

    /**
     * Only the slots the palette actually defines. Anything a dithering palette picks gets drawn, so
     * an undefined index must never make the list.
     */
    @Override
    public synchronized byte[] entries() {
        if (entries != null) return entries;

        java.util.List<Byte> usable = new java.util.ArrayList<>();
        for (int i = 4; i < 256; i++) {
            try {
                Color color = MapPalette.getColor((byte) i);
                if (color != null && color.getAlpha() == 255) {
                    usable.add((byte) i);
                }
            } catch (RuntimeException e) {
                // Not a color this palette defines.
            }
        }

        entries = new byte[usable.size()];
        for (int i = 0; i < entries.length; i++) entries[i] = usable.get(i);
        return entries;
    }

    /** Convenience for screens that want a palette index up front. */
    public static byte of(Color color) {
        return INSTANCE.index(color);
    }
}
