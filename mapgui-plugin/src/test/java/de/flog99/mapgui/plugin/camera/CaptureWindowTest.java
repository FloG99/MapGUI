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
        window.captured(4 * MS);

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
        window.captured(4 * MS);
        second += 3;

        assertEquals(0.25, window.read().perSecond(), 0.001);
        assertEquals(MS, window.read().mainNanosPerSecond());
    }

    @Test
    void anythingOlderThanTheWindowFallsOut() {
        CaptureWindow window = window();
        window.captured(4 * MS);

        second += 4;
        assertEquals(1, window.read().captures());

        second++;
        assertTrue(window.read().idle());
    }

    /** One long copy is a stutter a player sees, so it survives an average that would hide it. */
    @Test
    void theWorstCaptureIsKeptApartFromTheAverage() {
        CaptureWindow window = window();
        window.captured(2 * MS);
        window.captured(30 * MS);
        second++;
        window.captured(2 * MS);
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
        window.captured(MS);
        window.captured(MS);
        window.traced(100 * MS);
        second++;

        assertEquals(2, window.read().captures());
        assertEquals(100 * MS, window.read().traceNanosEach());
    }

    /** A tick is 50 ms and there are 20 a second, so 10 ms of main-thread work a second is one percent of the server. */
    @Test
    void theTickShareIsAgainstTheThousandMillisecondsASecondHolds() {
        CaptureWindow window = window();
        window.captured(40 * MS);
        second++;

        assertEquals(1.0, window.read().tickPercent(), 0.001);
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
