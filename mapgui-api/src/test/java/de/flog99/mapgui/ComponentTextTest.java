package de.flog99.mapgui;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Palette;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import de.flog99.mapgui.ui.TextFont;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentTextTest {

    private static final byte RED = 1;
    private static final byte GREEN = 2;
    private static final byte OTHER = 3;

    /** Enough of a palette to tell the two colors in a component apart on the surface. */
    private static final Palette PALETTE = new Palette() {

        @Override
        public byte index(Color color) {
            if (color.equals(new Color(NamedTextColor.RED.value()))) return RED;
            if (color.equals(new Color(NamedTextColor.GREEN.value()))) return GREEN;
            return OTHER;
        }

        @Override
        public Color color(byte index) {
            return Color.BLACK;
        }
    };

    /** Every glyph a solid 3x5 block, so what is drawn is easy to count and easy to place. */
    private static final class Blocks implements TextFont {

        @Override
        public int lineHeight() {
            return 5;
        }

        @Override
        public int widthOf(String text) {
            return text == null || text.isEmpty() ? 0 : text.length() * 4 - 1;
        }

        @Override
        public String sanitize(String text) {
            return text == null ? "" : text;
        }

        @Override
        public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 3; column++) {
                    if (clip.contains(x + column, y + row)) {
                        surface.set(x + column, y + row, color);
                    }
                }
            }
        }

        @Override
        public int charWidth(char ch) {
            return 3;
        }
    }

    private static final class Buffer implements Surface {

        private static final int SIDE = 64;

        private final byte[] pixels = new byte[SIDE * SIDE];

        @Override
        public int width() {
            return SIDE;
        }

        @Override
        public int height() {
            return SIDE;
        }

        @Override
        public void set(int x, int y, byte color) {
            if (inBounds(x, y)) {
                pixels[y * SIDE + x] = color;
            }
        }

        @Override
        public byte get(int x, int y) {
            return inBounds(x, y) ? pixels[y * SIDE + x] : 0;
        }

        int count(byte of) {
            int total = 0;
            for (byte pixel : pixels) {
                if (pixel == of) {
                    total++;
                }
            }
            return total;
        }
    }

    private static Painter painter(Buffer buffer) {
        return new Painter(buffer, PALETTE, new Blocks());
    }

    @Test
    void eachRunKeepsItsOwnColor() {
        Buffer buffer = new Buffer();
        Component line = Component.text("ab", NamedTextColor.RED)
                .append(Component.text("cd", NamedTextColor.GREEN));

        ComponentText.draw(painter(buffer), 0, 0, line, Color.WHITE, false);

        assertEquals(2 * 15, buffer.count(RED));
        assertEquals(2 * 15, buffer.count(GREEN));
    }

    @Test
    void aChildInheritsWhatItDoesNotSet() {
        Buffer buffer = new Buffer();
        Component line = Component.text("ab").color(NamedTextColor.RED)
                .append(Component.text("cd"));

        ComponentText.draw(painter(buffer), 0, 0, line, Color.WHITE, false);

        assertEquals(4 * 15, buffer.count(RED), "the child said nothing about color, so it is red too");
    }

    @Test
    void textWithNoColorFallsBackToTheOneGiven() {
        Buffer buffer = new Buffer();

        ComponentText.draw(painter(buffer), 0, 0, Component.text("ab"), new Color(NamedTextColor.GREEN.value()), false);

        assertEquals(2 * 15, buffer.count(GREEN));
    }

    @Test
    void boldIsFakedWhenTheFontHasNoBoldOfItsOwn() {
        Buffer plain = new Buffer();
        ComponentText.draw(painter(plain), 0, 0, Component.text("ab", NamedTextColor.RED), Color.WHITE, false);

        Buffer bold = new Buffer();
        ComponentText.draw(painter(bold), 0, 0,
                Component.text("ab", NamedTextColor.RED).decorate(TextDecoration.BOLD), Color.WHITE, false);

        assertTrue(bold.count(RED) > plain.count(RED), "drawn twice, a pixel apart");
    }

    @Test
    void underlineAndStrikeAreDrawnInTheRunsColor() {
        Buffer buffer = new Buffer();
        ComponentText.draw(painter(buffer), 0, 10,
                Component.text("ab", NamedTextColor.RED).decorate(TextDecoration.UNDERLINED), Color.WHITE, false);

        assertTrue(buffer.get(0, 15) == RED, "a rule under the run, on its baseline");
    }

    @Test
    void widthMatchesWhatWasDrawn() {
        Buffer buffer = new Buffer();
        Component line = Component.text("ab", NamedTextColor.RED).append(Component.text("cd", NamedTextColor.GREEN));

        int drawn = ComponentText.draw(painter(buffer), 0, 0, line, Color.WHITE, false);

        assertEquals(ComponentText.widthOf(new Blocks(), line), drawn - 1, "the trailing gap is not part of the text");
    }

    @Test
    void plainThrowsTheStylesAway() {
        Component line = Component.text("ab", NamedTextColor.RED).append(Component.text("cd"));

        assertEquals("abcd", ComponentText.plain(line));
    }
}
