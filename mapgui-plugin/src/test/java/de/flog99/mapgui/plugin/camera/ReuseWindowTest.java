package de.flog99.mapgui.plugin.camera;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rule behind all three caches, so it is worth its own tests - and most of these are about what happens when
 * somebody types a number into config.yml that does not mean what they thought it did.
 */
class ReuseWindowTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private static ReuseWindow window() {
        return ReuseWindow.ofMillis(500, 2000, 16, 64);
    }

    @Test
    void theNearWindowIsFlatUpToTheNearDistanceAndTheFarOneFlatPastTheFar() {
        ReuseWindow window = window();

        assertEquals(SECOND / 2, window.allowedAgeNanos(0));
        assertEquals(SECOND / 2, window.allowedAgeNanos(16));
        assertEquals(2 * SECOND, window.allowedAgeNanos(64));
        assertEquals(2 * SECOND, window.allowedAgeNanos(10_000));
    }

    /** Ramped rather than stepped, or something visibly catches up as you walk toward it. */
    @Test
    void itRampsEvenlyBetweenTheTwo() {
        ReuseWindow window = window();

        assertEquals(1.25 * SECOND, window.allowedAgeNanos(40), 1e6);

        long last = -1;
        for (double away = 0; away <= 80; away += 0.25) {
            long allowed = window.allowedAgeNanos(away);
            assertTrue(allowed >= last, "the window shortened at " + away);
            last = allowed;
        }
    }

    /**
     * A far window shorter than the near one is not a cache that grows more exact with distance, it is somebody
     * having swapped two lines. Raised to match rather than obeyed, since obeying it would make things nearer the
     * camera the stale ones - the exact opposite of what the whole rule is for.
     */
    @Test
    void aFarWindowShorterThanTheNearOneIsRaisedRatherThanObeyed() {
        ReuseWindow swapped = ReuseWindow.ofMillis(2000, 500, 16, 64);

        assertEquals(2 * SECOND, swapped.allowedAgeNanos(0));
        assertEquals(2 * SECOND, swapped.allowedAgeNanos(64));
    }

    /** The same for the distances, which would otherwise divide by nothing in the ramp. */
    @Test
    void aFarDistanceInsideTheNearOneDoesNotBreakTheRamp() {
        ReuseWindow crossed = ReuseWindow.ofMillis(500, 2000, 64, 16);

        assertEquals(SECOND / 2, crossed.allowedAgeNanos(0));
        assertEquals(SECOND / 2, crossed.allowedAgeNanos(64));
        assertEquals(2 * SECOND, crossed.allowedAgeNanos(65));
    }

    /** Negatives read as nothing rather than as a window that runs backwards. */
    @Test
    void negativeNumbersFloorAtNothing() {
        ReuseWindow negative = ReuseWindow.ofMillis(-500, -2000, -8, -1);

        assertEquals(0, negative.allowedAgeNanos(0));
        assertEquals(0, negative.allowedAgeNanos(1000));
        assertFalse(negative.enabled());
    }

    /** Zeroes everywhere are how a server turns one of these off, so that has to read as off. */
    @Test
    void allZeroesIsAWindowThatHoldsNothing() {
        assertFalse(ReuseWindow.NONE.enabled());
        assertFalse(ReuseWindow.ofMillis(0, 0, 16, 64).enabled());
        assertTrue(window().enabled());
    }

    /** Read back in the unit config.yml writes, since that is where the defaults come from when a key is unset. */
    @Test
    void itReadsBackInTheMillisecondsItWasWrittenIn() {
        assertEquals(500, window().nearMillis());
        assertEquals(2000, window().farMillis());
    }

    /** An expiry sweep has to keep whatever the longest reader could still ask for. */
    @Test
    void theLongestIsTheFarWindow() {
        assertEquals(2 * SECOND, window().longestNanos());
    }
}
