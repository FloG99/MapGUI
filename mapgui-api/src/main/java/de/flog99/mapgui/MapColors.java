package de.flog99.mapgui;

import de.flog99.mapgui.ui.Palette;
import de.flog99.mapgui.ui.PaletteLut;
import org.bukkit.map.MapPalette;

import java.awt.Color;

/**
 * Bukkit's map palette, answered from a lookup table.
 *
 * <p>{@code MapPalette.matchColor} walks the whole palette on every call, which is brutal per pixel, so it is
 * called 32768 times on first use to fill a {@link PaletteLut} and never again. That makes matching a shift and
 * an array read, with nothing allocated and nothing to synchronize - which is what lets a camera or a video
 * frame be quantized off the main thread.
 *
 * <p>The table is built on first use rather than at startup, so nothing pays for it that never draws.
 */
@SuppressWarnings("removal") // Color matching is deprecated for removal with no replacement offered.
public final class MapColors implements Palette {

    public static final MapColors INSTANCE = new MapColors();

    private static final Color UNDEFINED = new Color(0, 0, 0);

    private final Color[] toColor = new Color[256];
    private byte[] entries;

    private MapColors() {
    }

    /** Built when something first asks for a color, which is well after {@link #INSTANCE} exists. */
    private static final class Table {

        static final PaletteLut LUT = new PaletteLut(new Matcher());
    }

    /** Bukkit's own matching, used only to fill the table. */
    private static final class Matcher implements Palette {

        @Override
        public byte index(Color color) {
            return MapPalette.matchColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
        }

        @Override
        public Color color(byte index) {
            return INSTANCE.color(index);
        }

        @Override
        public byte[] entries() {
            return INSTANCE.entries();
        }
    }

    @Override
    public byte index(Color color) {
        return Table.LUT.index(color);
    }

    /** The same from a packed int, for anything holding pixels rather than colors. */
    public byte index(int argb) {
        return Table.LUT.index(argb);
    }

    @Override
    public byte index(int argb, int x, int y) {
        return Table.LUT.index(argb);
    }

    /**
     * Fills the table now rather than the first time something draws.
     *
     * <p>Worth doing at startup for two reasons: it is 32768 searches through Bukkit's palette, which is a
     * visible hitch mid-frame, and Bukkit's own colour cache is not built to be raced - so the first caller
     * should not be a decoder thread that got there before the server finished starting.
     */
    public static void warmUp() {
        INSTANCE.index(Color.BLACK);
    }

    /** A whole frame at once, into indices somebody else owns. */
    public void quantize(int[] argb, byte[] out) {
        Table.LUT.quantize(argb, out);
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
