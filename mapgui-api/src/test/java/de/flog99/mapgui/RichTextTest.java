package de.flog99.mapgui;

import de.flog99.mapgui.ui.LayoutContext;
import de.flog99.mapgui.ui.Measured;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import de.flog99.mapgui.ui.TextFont;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichTextTest {

    /** Every glyph three wide and five tall, so a width is countable rather than font-dependent. */
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
        }

        @Override
        public int charWidth(char ch) {
            return 3;
        }
    }

    private static Measured measure(RichText node, int available) {
        return node.measure(new LayoutContext(new Blocks()), available, 128);
    }

    @Test
    void itMeasuresTheWayComponentTextDraws() {
        Component line = Component.text("ab", NamedTextColor.RED).append(Component.text("cd"));
        RichText node = RichText.of(line);

        Measured measured = measure(node, 128);

        assertEquals(ComponentText.widthOf(new Blocks(), line), measured.width());
        assertEquals(5, measured.height(), "one line of the font it was measured with");
    }

    @Test
    void itIsNeverWiderThanTheSpaceItWasOffered() {
        RichText node = RichText.of(Component.text("a very long line of text indeed"));

        assertTrue(measure(node, 20).width() <= 20);
    }

    @Test
    void anEmptyComponentTakesNoSpace() {
        assertEquals(0, measure(RichText.of(Component.empty()), 128).width());
        assertEquals(0, measure(RichText.of(Component.empty()), 128).height());
    }

    @Test
    void aSupplierIsReadEachTimeRatherThanOnce() {
        StringBuilder shown = new StringBuilder("a");
        RichText node = RichText.of(() -> Component.text(shown.toString()));

        int first = measure(node, 128).width();
        shown.append("bcd");

        assertTrue(measure(node, 128).width() > first, "the tree was not rebuilt, and it still noticed");
    }
}
