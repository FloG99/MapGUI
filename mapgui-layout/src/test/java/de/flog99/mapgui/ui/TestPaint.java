package de.flog99.mapgui.ui;

import java.awt.Color;

/** A painter that draws nowhere, for tests that only care about what layout asks for. */
final class TestPaint {

    private TestPaint() {
    }

    static Painter painter() {
        return painter(TestFont.INSTANCE);
    }

    /** The same, drawn with a font of the caller's choosing - for tests about what a font is asked. */
    static Painter painter(TextFont font) {
        Surface nowhere = new Surface() {
            @Override
            public int width() {
                return 128;
            }

            @Override
            public int height() {
                return 128;
            }

            @Override
            public void set(int x, int y, byte color) {
            }

            @Override
            public byte get(int x, int y) {
                return 0;
            }
        };

        Palette flat = new Palette() {
            @Override
            public byte index(Color color) {
                return 1;
            }

            @Override
            public Color color(byte index) {
                return Color.BLACK;
            }
        };

        return new Painter(nowhere, flat, font);
    }
}
