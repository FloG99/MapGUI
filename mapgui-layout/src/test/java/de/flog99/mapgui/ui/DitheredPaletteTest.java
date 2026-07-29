package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DitheredPaletteTest {

    /**
     * A deliberately sparse palette, like the real one: a handful of base colors with no smooth
     * ramp between them, which is what makes a gradient band.
     */
    private static final Color[] SPARSE = {
            new Color(0, 0, 0),
            new Color(64, 64, 64),
            new Color(128, 128, 128),
            new Color(192, 192, 192),
            new Color(255, 255, 255),
            new Color(200, 40, 40),
            new Color(40, 160, 60),
            new Color(50, 80, 200),
    };

    private static final Palette BASE = new Palette() {
        @Override
        public byte index(Color color) {
            int best = 0;
            long closest = Long.MAX_VALUE;
            for (int i = 0; i < SPARSE.length; i++) {
                long dr = color.getRed() - SPARSE[i].getRed();
                long dg = color.getGreen() - SPARSE[i].getGreen();
                long db = color.getBlue() - SPARSE[i].getBlue();
                long distance = dr * dr + dg * dg + db * db;
                if (distance < closest) {
                    closest = distance;
                    best = i;
                }
            }
            return (byte) best;
        }

        @Override
        public Color color(byte index) {
            int slot = index & 0xFF;
            return slot < SPARSE.length ? SPARSE[slot] : null;
        }
    };

    @Test
    void onlyRealEntriesAreOffered() {
        assertEquals(SPARSE.length, BASE.entries().length);
    }

    /** The whole point: more apparent shades than the palette actually contains. */
    @Test
    void ditheringBeatsBandingAcrossARamp() {
        Palette dithered = new DitheredPalette(BASE);

        Set<Byte> flat = new HashSet<>();
        Set<String> ditheredPatterns = new HashSet<>();

        for (int x = 0; x < 64; x++) {
            Color wanted = Colors.mix(Color.BLACK, Color.WHITE, x / 63.0);
            flat.add(BASE.index(wanted));

            // A column's pattern across four rows is what the eye averages into a shade.
            StringBuilder column = new StringBuilder();
            for (int y = 0; y < 4; y++) column.append(dithered.index(wanted, x, y)).append(',');
            ditheredPatterns.add(column.toString());
        }

        assertEquals(5, flat.size(), "the palette only has five grays to snap to");
        assertTrue(ditheredPatterns.size() > flat.size() * 2,
                "dithering should produce far more apparent shades, got " + ditheredPatterns.size()
        );
    }

    @Test
    void anExactPaletteColourIsNeverDithered() {
        Palette dithered = new DitheredPalette(BASE);

        for (Color exact : SPARSE) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    assertEquals(BASE.index(exact), dithered.index(exact, x, y),
                            "a color the palette already has must not be mixed with anything"
                    );
                }
            }
        }
    }

    @Test
    void everyChosenIndexIsARealEntry() {
        Palette dithered = new DitheredPalette(BASE);
        Set<Byte> allowed = new HashSet<>();
        for (byte entry : BASE.entries()) allowed.add(entry);

        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 4; y++) {
                Color wanted = Colors.mix(new Color(200, 40, 40), new Color(50, 80, 200), x / 31.0);
                assertTrue(allowed.contains(dithered.index(wanted, x, y)), "picked an index that isn't in the palette");
            }
        }
    }

    @Test
    void translucentColoursFallBackToPlainMatching() {
        Palette dithered = new DitheredPalette(BASE);
        Color faded = new Color(120, 120, 120, 128);

        assertEquals(BASE.index(faded), dithered.index(faded, 1, 2));
    }
}
