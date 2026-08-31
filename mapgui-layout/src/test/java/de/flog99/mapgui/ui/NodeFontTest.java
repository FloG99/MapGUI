package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static de.flog99.mapgui.ui.Ui.Column;
import static de.flog99.mapgui.ui.Ui.Draw;
import static de.flog99.mapgui.ui.Ui.Text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A font set on a node reaches its whole subtree and nothing outside it - in <b>both</b> passes, since a layout
 * sized with one font and drawn with another puts the words in the wrong place.
 */
class NodeFontTest {

    private static final int CANVAS = 128;

    /** Twice as wide per character as {@link TestFont}, so a measurement says which font did it. */
    private static final class Wide implements TextFont {

        private final int perChar;

        Wide(int perChar) {
            this.perChar = perChar;
        }

        @Override
        public int lineHeight() {
            return 8;
        }

        @Override
        public int widthOf(String text) {
            return text == null || text.isEmpty() ? 0 : text.length() * perChar;
        }

        @Override
        public int charWidth(char ch) {
            return perChar;
        }

        @Override
        public String sanitize(String text) {
            return text == null ? "" : text;
        }

        @Override
        public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
        }
    }

    private static final TextFont WIDE = new Wide(20);
    private static final TextFont WIDER = new Wide(40);

    private void layout(Node root) {
        LayoutContext context = new LayoutContext(TestFont.INSTANCE, new Animator());
        root.measure(context, CANVAS, CANVAS);
        root.arrange(context, new Rect(0, 0, CANVAS, CANVAS));
    }

    @Test
    void aNodeFontMeasuresItsOwnSubtree() {
        Label plain = Text("abc");
        Label wide = Text("abc");
        layout(Column(plain, Column(wide).font(WIDE)));

        assertEquals(TestFont.INSTANCE.widthOf("abc"), plain.bounds().width());
        assertEquals(WIDE.widthOf("abc"), wide.bounds().width());
    }

    /** The nearest font wins, and the one it displaced is back in place for the next sibling. */
    @Test
    void theNearestFontWinsAndTheOuterOneComesBack() {
        Label inner = Text("abc");
        Label after = Text("abc");
        layout(Column(Column(Column(inner).font(WIDER), after).font(WIDE)));

        assertEquals(WIDER.widthOf("abc"), inner.bounds().width());
        assertEquals(WIDE.widthOf("abc"), after.bounds().width());
    }

    /** Painting has to agree with measuring, or a label sits where the wrong font put it. */
    @Test
    void aNodeFontPaintsItsOwnSubtree() {
        List<TextFont> seen = new ArrayList<>();
        Node root = Column(
                Draw(paint -> seen.add(paint.painter().font())),
                Column(Draw(paint -> seen.add(paint.painter().font()))).font(WIDE),
                Draw(paint -> seen.add(paint.painter().font()))
        );

        layout(root);
        Painter painter = new Painter(new Blank(), new Flat(), TestFont.INSTANCE);
        root.paint(painter);

        assertEquals(List.of(TestFont.INSTANCE, WIDE, TestFont.INSTANCE), seen);
        assertSame(TestFont.INSTANCE, painter.font(), "the painter should be handed back as it was found");
    }

    /** No font on any node is the screen's font all the way down, and nothing allocates a context for it. */
    @Test
    void withoutAFontNothingChanges() {
        LayoutContext context = new LayoutContext(TestFont.INSTANCE, new Animator());

        assertSame(context, context.withFont(null));
        assertSame(context, context.withFont(TestFont.INSTANCE));
        assertTrue(context.withFont(WIDE).font() == WIDE);
    }

    private static final class Blank implements Surface {

        @Override
        public int width() {
            return CANVAS;
        }

        @Override
        public int height() {
            return CANVAS;
        }

        @Override
        public void set(int x, int y, byte color) {
        }

        @Override
        public byte get(int x, int y) {
            return 0;
        }
    }

    private static final class Flat implements Palette {

        @Override
        public byte index(Color color) {
            return 1;
        }

        @Override
        public Color color(byte index) {
            return Color.BLACK;
        }
    }
}
