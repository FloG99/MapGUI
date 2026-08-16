package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A/B equivalence for the primitive blend cache: the packed entry point must answer exactly what the
 * {@link Color} entry point did before the memo was replaced - every opaque color, at every threshold
 * position, picks the same two palette entries and the same ratio. The cache only changes <i>how</i> a
 * result is looked up, never <i>which</i> result.
 */
class DitheredPaletteAbTest {

    /** A base palette with a handful of distinct colors so dithering has real pairs to pick. */
    private static final Palette BASE = new Palette() {
        private final Color[] colors = {
                new Color(0, 0, 0),
                new Color(255, 0, 0),
                new Color(0, 255, 0),
                new Color(0, 0, 255),
                new Color(255, 255, 255),
                new Color(128, 128, 128),
        };

        @Override
        public byte index(Color color) {
            byte best = 0;
            long bestDist = Long.MAX_VALUE;
            for (byte i = 0; i < colors.length; i++) {
                int dr = color.getRed() - colors[i].getRed();
                int dg = color.getGreen() - colors[i].getGreen();
                int db = color.getBlue() - colors[i].getBlue();
                long d = (long) dr * dr + (long) dg * dg + (long) db * db;
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return best;
        }

        @Override
        public Color color(byte index) {
            return colors[(index & 0xFF) % colors.length];
        }
    };

    @Test
    void packedIndexMatchesColorIndexForEveryOpaqueColorAndPosition() {
        DitheredPalette palette = new DitheredPalette(BASE);
        Random random = new Random(42);

        for (int i = 0; i < 500; i++) {
            int rgb = random.nextInt(0x1000000);
            Color color = new Color(rgb);
            int argb = 0xFF000000 | rgb;

            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) {
                    int fx = x;
                    int fy = y;
                    int frgb = rgb;
                    byte viaColor = palette.index(color, x, y);
                    byte viaInt = palette.index(argb, x, y);
                    assertEquals(viaColor, viaInt,
                            () -> "color " + Integer.toHexString(frgb) + " at (" + fx + "," + fy + ")");
                }
            }
        }
    }

    @Test
    void packedIndexMatchesColorIndexForOpaqueBlack() {
        DitheredPalette palette = new DitheredPalette(BASE);
        Color black = new Color(0, 0, 0);
        int blackArgb = 0xFF000000;

        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                byte viaColor = palette.index(black, x, y);
                byte viaInt = palette.index(blackArgb, x, y);
                assertEquals(viaColor, viaInt, "opaque black at (" + x + "," + y + ")");
            }
        }
    }

    @Test
    void opaqueBlackSurvivesCacheGrowth() {
        DitheredPalette palette = new DitheredPalette(BASE);
        // Establish opaque black before growth.
        byte beforeInt = palette.index(0xFF000000, 0, 0);
        byte beforeColor = palette.index(new Color(0, 0, 0), 0, 0);
        assertEquals(beforeInt, beforeColor, "opaque black before growth");

        // Touch many opaque colors to force cache growth.
        for (int i = 1; i < 200; i++) {
            palette.index(0xFF000000 | (i % 0x1000000), i % 4, (i / 4) % 4);
        }

        // Query opaque black again after growth.
        byte afterInt = palette.index(0xFF000000, 0, 0);
        byte afterColor = palette.index(new Color(0, 0, 0), 0, 0);
        assertEquals(beforeInt, afterInt, "packed black stable after growth");
        assertEquals(beforeColor, afterColor, "color black stable after growth");
    }
    @Test
    void translucentPackedIndexFallsBackLikeTheColorPath() {
        DitheredPalette palette = new DitheredPalette(BASE);
        // alpha 0x80: the Color path goes to the base palette; the packed path must too.
        Color translucent = new Color(10, 20, 30, 128);
        assertEquals(palette.index(translucent, 1, 1), palette.index(translucent.getRGB(), 1, 1));
    }
}
