package de.flog99.mapgui.plugin.camera;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureWindowTest {

    private static final long MS = 1_000_000;

    private long second = 1_000_000;

    private CaptureWindow window() {
        return new CaptureWindow(() -> second);
    }

    @Test
    void aWindowNothingHasHappenedInIsIdle() {
        assertTrue(window().read().idle());
    }

    /** The second still being filled is left out, the way bandwidth leaves it out, or every reading reads low. */
    @Test
    void theSecondInProgressIsNotCountedYet() {
        CaptureWindow window = window();
        window.captured(4 * MS, 0, 0, true);

        assertEquals(0, window.read().captures());

        second++;
        assertEquals(1, window.read().captures());
    }

    /**
     * The whole window divides a rate, not the seconds that happened to have something in them. Captures come in
     * bursts, and counting only the busy seconds would report one capture four seconds ago as one a second - which
     * is the difference between a camera nobody is using and one that is costing something.
     */
    @Test
    void aRateIsSpreadOverTheWholeWindowRatherThanTheBusySeconds() {
        CaptureWindow window = window();
        window.captured(4 * MS, 0, 0, true);
        second += 3;

        assertEquals(0.25, window.read().perSecond(), 0.001);
        assertEquals(MS, window.read().mainNanosPerSecond());
    }

    @Test
    void anythingOlderThanTheWindowFallsOut() {
        CaptureWindow window = window();
        window.captured(4 * MS, 0, 0, true);

        second += 4;
        assertEquals(1, window.read().captures());

        second++;
        assertTrue(window.read().idle());
    }

    /** One long copy is a stutter a player sees, so it survives an average that would hide it. */
    @Test
    void theWorstCaptureIsKeptApartFromTheAverage() {
        CaptureWindow window = window();
        window.captured(2 * MS, 0, 0, true);
        window.captured(30 * MS, 0, 0, true);
        second++;
        window.captured(2 * MS, 0, 0, true);
        second++;

        CaptureWindow.Load load = window.read();
        assertEquals(30 * MS, load.worstMainNanos());
        assertEquals(34 * MS / 4, load.mainNanosPerSecond());
    }

    /**
     * The trace is averaged over the traces, not over the captures. They are counted in different seconds on
     * purpose - a capture is charged to the tick it copied the world in, and its trace finishes whenever a thread
     * gets to it - so dividing one by the other would report a made-up number whenever the pool is behind.
     */
    @Test
    void traceIsAveragedOverTheOnesThatFinished() {
        CaptureWindow window = window();
        window.captured(MS, 0, 0, true);
        window.captured(MS, 0, 0, true);
        window.traced(100 * MS);
        second++;

        assertEquals(2, window.read().captures());
        assertEquals(100 * MS, window.read().traceNanosEach());
    }

    /** A tick is 50 ms and there are 20 a second, so 10 ms of main-thread work a second is one percent of the server. */
    @Test
    void theTickShareIsAgainstTheThousandMillisecondsASecondHolds() {
        CaptureWindow window = window();
        window.captured(40 * MS, 0, 0, true);
        second++;

        assertEquals(1.0, window.read().tickPercent(), 0.001);
    }

    /**
     * A capture that never asked to be paced is counted apart, so a budget nothing is honouring can be said out
     * loud rather than looking like a budget nothing needed.
     */
    @Test
    void capturesThatDidNotAskAreCountedApartFromTheOnesThatDid() {
        CaptureWindow window = window();
        window.captured(MS, 0, 0, true);
        window.captured(MS, 0, 0, false);
        window.captured(MS, 0, 0, false);
        second++;

        CaptureWindow.Load load = window.read();
        assertEquals(3, load.captures());
        assertEquals(1, load.paced());
        assertEquals(0.5, load.unpacedPerSecond(), 0.001);
    }

    /**
     * A capture turned away for want of a thread is not a capture that failed. Nothing broke, so it is counted on
     * its own - and it still means the window is not idle, or being over capacity would report as being quiet.
     */
    @Test
    void capturesTurnedAwayAreCountedApartFromFailures() {
        CaptureWindow window = window();
        window.turnedAway();
        second++;

        CaptureWindow.Load load = window.read();
        assertEquals(1, load.dropped());
        assertEquals(0, load.failed());
        assertEquals(0, load.captures());
        assertFalse(load.idle());
    }

    /** The tick half is split, since a big copy and a big entity gather want different things done about them. */
    @Test
    void copyAndEntityTimeAreCountedApart() {
        CaptureWindow window = window();
        window.captured(4 * MS, MS, 0, true);
        window.captured(6 * MS, 3 * MS, 0, true);
        second++;

        CaptureWindow.Load load = window.read();
        assertEquals(5 * MS, load.copyNanosEach());
        assertEquals(2 * MS, load.mobNanosEach());
        assertEquals(7 * MS, load.mainNanosEach());
    }

    /** A tick is a twentieth of a second, and the budget an admin sets is written per tick. */
    @Test
    void theMainThreadCostIsAlsoReadablePerTick() {
        CaptureWindow window = window();
        window.captured(20 * MS, 0, 0, true);
        second++;

        CaptureWindow.Load load = window.read();
        assertEquals(5 * MS, load.mainNanosPerSecond());
        assertEquals(MS / 4, load.mainNanosPerTick());
    }

    /** A camera that only fails is not idle, or the one state worth reporting would be the one that reports nothing. */
    @Test
    void failuresAloneCountAsSomethingHappening() {
        CaptureWindow window = window();
        window.failed();
        second++;

        assertFalse(window.read().idle());
        assertEquals(1, window.read().failed());
        assertEquals(0, window.read().captures());
    }
}
