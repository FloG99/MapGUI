package de.flog99.mapgui;

import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.TextFont;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cutting text to fit, against the obvious way of doing it.
 *
 * <p>Both {@link Painter#ellipsize} and {@link TextFont#longestPrefix} halve into the answer rather than
 * stepping through it, which is only the same answer while a longer prefix is never narrower than a shorter
 * one. That holds for any font whose characters have a width, but it is an assumption the stepping form did
 * not need - so the two are compared here on every shape of input rather than trusted to agree.
 */
class EllipsizeTest {

    private static final TextFont FONT = MapTextFont.INSTANCE;

    private static final Painter PAINTER = new Painter(new MapSurface(128, 128), MapColors.INSTANCE, FONT);

    /** Stepping down a character at a time, which is what {@code ellipsize} used to do. */
    private static String steppedEllipsize(String text, int maxWidth) {
        String clean = FONT.sanitize(text);
        if (FONT.widthOf(clean) <= maxWidth || clean.length() == 1) return clean;

        for (int length = clean.length() - 1; length > 0; length--) {
            String candidate = clean.substring(0, length) + "..";
            if (FONT.widthOf(candidate) <= maxWidth) return candidate;
        }
        return "..";
    }

    /** Growing a character at a time, which is what {@code longestPrefix} used to do. */
    private static int steppedPrefix(String text, int maxWidth) {
        int fits = 0;
        while (fits < text.length() && FONT.widthOf(text.substring(0, fits + 1)) <= maxWidth) {
            fits++;
        }
        return fits;
    }

    @Test
    void bothAgreeWithSteppingOnAnythingAtAnyWidth() {
        Random random = new Random(3);
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,!'\"@#-_()[]{}|/\\";

        for (int attempt = 0; attempt < 20000; attempt++) {
            StringBuilder text = new StringBuilder();
            for (int i = 1 + random.nextInt(120); i > 0; i--) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String value = text.toString();
            // Past the widest line as well as under it, so both the everything-fits and nothing-fits ends are hit.
            int maxWidth = random.nextInt(200);

            assertEquals(steppedEllipsize(value, maxWidth), PAINTER.ellipsize(value, maxWidth),
                    "ellipsize at width " + maxWidth + " of \"" + value + "\"");
            assertEquals(steppedPrefix(value, maxWidth), FONT.longestPrefix(value, maxWidth),
                    "longestPrefix at width " + maxWidth + " of \"" + value + "\"");
        }
    }

    @Test
    void whatComesBackActuallyFits() {
        String blurb = "A wall is several maps, so a frame is several packets, and the client draws what arrived.";

        for (int maxWidth = 0; maxWidth <= 160; maxWidth++) {
            String cut = PAINTER.ellipsize(blurb, maxWidth);
            // ".." is the floor - there is nothing shorter to say - so only a wider answer has to fit.
            if (!cut.equals("..")) {
                assertTrue(FONT.widthOf(cut) <= maxWidth, "\"" + cut + "\" does not fit " + maxWidth);
            }
            assertTrue(FONT.widthOf(blurb.substring(0, FONT.longestPrefix(blurb, maxWidth))) <= maxWidth);
        }
    }

    @Test
    void textThatFitsIsLeftAlone() {
        assertEquals("Settings", PAINTER.ellipsize("Settings", 128));
        assertEquals(8, FONT.longestPrefix("Settings", 128));
    }

    @Test
    void asingleGlyphIsKeptEvenWhenItDoesNotFit() {
        assertEquals("W", PAINTER.ellipsize("W", 1), "\"..\" is no narrower and says less");
        assertEquals(0, FONT.longestPrefix("W", 1));
    }

    @Test
    void nothingFitsInNoRoomAtAll() {
        assertEquals("..", PAINTER.ellipsize("Settings", 0));
        assertEquals(0, FONT.longestPrefix("Settings", 0));
    }
}
