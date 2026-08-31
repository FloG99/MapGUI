package de.flog99.mapgui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which buttons a screen answers to, which decides both what it hears and what the player loses: every value but
 * {@link Click#NONE} swallows a click aimed at the screen, so a mistake here is a bow that silently stops working.
 */
class ClickTest {

    @Test
    void oneButtonTakesOnlyThatButton() {
        assertTrue(Click.RIGHT.accepts(Click.RIGHT));
        assertFalse(Click.RIGHT.accepts(Click.LEFT));

        assertTrue(Click.LEFT.accepts(Click.LEFT));
        assertFalse(Click.LEFT.accepts(Click.RIGHT));
    }

    @Test
    void bothTakesEither() {
        assertTrue(Click.BOTH.accepts(Click.RIGHT));
        assertTrue(Click.BOTH.accepts(Click.LEFT));
    }

    /**
     * The one that would otherwise fall out of {@code this == button} by accident. {@code NONE} is an answer about
     * buttons rather than one of them, so it matches nothing - including itself, which is the case a caller reaches
     * by passing a screen's own answer back in.
     */
    @Test
    void noneTakesNothingAtAll() {
        for (Click button : Click.values()) {
            assertFalse(Click.NONE.accepts(button), "NONE should accept nothing, but accepted " + button);
        }
    }

    /** Nothing but BOTH answers to a button it was not set to, which is what stops a screen eating the other one. */
    @Test
    void nothingElseTakesNone() {
        assertFalse(Click.RIGHT.accepts(Click.NONE));
        assertFalse(Click.LEFT.accepts(Click.NONE));
        assertFalse(Click.BOTH.accepts(Click.NONE),
                "BOTH means either real button, not the absence of one");
    }
}
