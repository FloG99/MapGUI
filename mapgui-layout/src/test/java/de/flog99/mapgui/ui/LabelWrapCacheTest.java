package de.flog99.mapgui.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wrapped label holds on to the lines it wrapped, since one frame asks for them four times - once per
 * measure pass and again to paint them - and wrapping is the most expensive thing a font is asked to do.
 *
 * <p>Which is only safe while it cannot hand back lines for text it no longer shows, a width it no longer
 * has, or a font it was not measured with. That is what these are about.
 */
class LabelWrapCacheTest {

    private static final Rect SCREEN = new Rect(0, 0, 128, 128);

    private static final String BLURB = "one two three four five six seven eight nine ten eleven twelve";

    /** Records every wrap it is asked for, so a frame's worth can be counted. */
    private static final class CountingFont implements TextFont {

        private final TextFont real = TestFont.INSTANCE;
        final List<String> wraps = new ArrayList<>();

        @Override
        public int lineHeight() {
            return real.lineHeight();
        }

        @Override
        public int widthOf(String text) {
            return real.widthOf(text);
        }

        @Override
        public String sanitize(String text) {
            return real.sanitize(text);
        }

        @Override
        public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
            real.drawChar(surface, x, y, ch, color, clip);
        }

        @Override
        public int charWidth(char ch) {
            return real.charWidth(ch);
        }

        @Override
        public List<String> wrap(String text, int maxWidth) {
            wraps.add(maxWidth + ":" + text);
            return real.wrap(text, maxWidth);
        }
    }

    /** One frame: measure, arrange, paint - the order a session runs them in. */
    private static void frame(Node root, TextFont font, Painter painter) {
        LayoutContext context = new LayoutContext(font);
        root.measure(context, SCREEN.width(), SCREEN.height());
        root.arrange(context, SCREEN);
        root.paint(painter);
    }

    @Test
    void oneFrameWrapsTheSameTextOnce() {
        CountingFont font = new CountingFont();
        Label label = Ui.Text(BLURB).wrap().fill();

        frame(Ui.Column(label).padding(4).align(Align.STRETCH).fill(), font, TestPaint.painter(font));

        assertEquals(1, font.wraps.size(), "the measure passes and the paint should share one wrap");
    }

    @Test
    void changingTheTextWrapsAgain() {
        CountingFont font = new CountingFont();
        String[] shown = {BLURB};
        Label label = Ui.Text(() -> shown[0]).wrap().fill();
        Node root = Ui.Column(label).padding(4).align(Align.STRETCH).fill();

        frame(root, font, TestPaint.painter(font));
        shown[0] = "something else entirely to say here instead";
        frame(root, font, TestPaint.painter(font));

        assertEquals(2, font.wraps.size());
        assertTrue(font.wraps.get(1).endsWith(shown[0]), "the new text has to be the one wrapped");
    }

    @Test
    void anarrowerBoxWrapsAgain() {
        CountingFont font = new CountingFont();
        Label label = Ui.Text(BLURB).wrap().fill();
        Node root = Ui.Column(label).padding(4).align(Align.STRETCH).fill();

        frame(root, font, TestPaint.painter(font));
        int wide = font.wraps.size();

        LayoutContext context = new LayoutContext(font);
        Rect narrow = new Rect(0, 0, 60, 128);
        root.measure(context, narrow.width(), narrow.height());
        root.arrange(context, narrow);
        root.paint(TestPaint.painter(font));

        assertTrue(font.wraps.size() > wide, "a different width is a different set of lines");
        assertTrue(font.wraps.getLast().startsWith("52:"), "wrapped for the narrow box, minus the padding");
    }

    /** A painter is pointed at the screen's font per frame, and a stacked screen can bring another one. */
    @Test
    void adifferentFontWrapsAgain() {
        CountingFont measured = new CountingFont();
        CountingFont drawn = new CountingFont();
        Label label = Ui.Text(BLURB).wrap().fill();
        Node root = Ui.Column(label).padding(4).align(Align.STRETCH).fill();

        LayoutContext context = new LayoutContext(measured);
        root.measure(context, SCREEN.width(), SCREEN.height());
        root.arrange(context, SCREEN);
        root.paint(TestPaint.painter(drawn));

        assertEquals(1, measured.wraps.size());
        assertEquals(1, drawn.wraps.size(), "lines wrapped with one font must not be drawn with another");
    }

    @Test
    void theLinesAreStillTheRightOnes() {
        CountingFont font = new CountingFont();
        Label label = Ui.Text(BLURB).wrap().fill();

        frame(Ui.Column(label).padding(4).align(Align.STRETCH).fill(), font, TestPaint.painter(font));

        assertEquals(TestFont.INSTANCE.wrap(BLURB, 120), font.wrap(BLURB, 120),
                "whatever is cached, it is what the font would have said");
    }
}
