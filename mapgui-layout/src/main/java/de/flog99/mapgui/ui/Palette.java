package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps RGB to and from map palette indices. Implemented against Bukkit's MapPalette. */
public interface Palette {

    byte index(Color color);

    Color color(byte index);

    /**
     * Same, but told where the pixel is going.
     *
     * <p>Only matters to a palette that dithers: choosing between two entries by position is the
     * whole mechanism, and a color on its own carries no position. Palettes that just snap to the
     * nearest entry ignore it.
     */
    default byte index(Color color, int x, int y) {
        return index(color);
    }

    /**
     * The same from a packed pixel.
     *
     * <p>For the per-pixel paths - blending, quantizing an image - where making a {@link Color} to ask with
     * would be an object per pixel. The default does exactly that, so a palette need only override it if it
     * cares; one backed by a table answers straight from the bits.
     */
    default byte index(int argb, int x, int y) {
        return index(new Color(argb, true), x, y);
    }

    /**
     * Indices this palette can actually produce, for anything that needs to pick between entries.
     *
     * <p>The default scans every slot and keeps one per distinct color, which is enough for a
     * palette whose unused slots report something repeated.
     */
    default byte[] entries() {
        List<Byte> found = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();

        for (int i = 0; i < 256; i++) {
            Color color = color((byte) i);
            if (color == null || color.getAlpha() < 255) continue;
            if (seen.add(color.getRGB())) {
                found.add((byte) i);
            }
        }

        byte[] entries = new byte[found.size()];
        for (int i = 0; i < entries.length; i++) entries[i] = found.get(i);
        return entries;
    }
}
