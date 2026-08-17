package de.flog99.mapgui.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WheelTest {

    /** One notch a tick, with the selection put back each time, is what a slow scroll looks like. */
    @Test
    void countsEveryNotchOfASlowScroll() {
        Wheel wheel = new Wheel();

        assertEquals(-1, wheel.turned(-1));
        assertEquals(-1, wheel.turned(-1));
        assertEquals(-1, wheel.turned(-1));
    }

    /** The bug this exists for: three notches inside one tick arrive as one report three slots away. */
    @Test
    void countsAFlickThatLandsInOneTick() {
        assertEquals(-3, new Wheel().turned(-3));
    }

    /** While the selection has not been put back yet, the reports grow - and only the growth is new. */
    @Test
    void countsOnlyWhatIsNewWhileTheAnswerIsInFlight() {
        Wheel wheel = new Wheel();

        assertEquals(-1, wheel.turned(-1));
        assertEquals(-1, wheel.turned(-2));
        assertEquals(-1, wheel.turned(-3));
    }

    /** Same, for a flick that keeps landing several notches at a time. */
    @Test
    void countsAFlickThatKeepsGoing() {
        Wheel wheel = new Wheel();

        assertEquals(-3, wheel.turned(-3));
        assertEquals(-3, wheel.turned(-6));
    }

    /** Turning back the other way is a fresh turn, never a drift carried on. */
    @Test
    void countsAChangeOfDirectionWhole() {
        Wheel wheel = new Wheel();

        assertEquals(-3, wheel.turned(-3));
        assertEquals(2, wheel.turned(2));
    }

    /** The same distance twice means the selection went back and out again, which is a notch each time. */
    @Test
    void countsARepeatedDistanceEachTime() {
        Wheel wheel = new Wheel();

        assertEquals(2, wheel.turned(2));
        assertEquals(2, wheel.turned(2));
    }

    /** A flick after a pause is a whole flick: the drift it would otherwise be read against is long since gone. */
    @Test
    void countsAFlickAfterAPauseWhole() {
        Wheel wheel = new Wheel();

        assertEquals(-1, wheel.turned(-1));
        wheel.settled();
        assertEquals(-3, wheel.turned(-3));
    }

    /** A report from the slot the map is in is the selection landing back where it belongs, and no turn at all. */
    @Test
    void countsNothingForTheSelectionComingHome() {
        Wheel wheel = new Wheel();

        assertEquals(-1, wheel.turned(-1));
        assertEquals(0, wheel.turned(0));
        assertEquals(-1, wheel.turned(-1));
    }
}
