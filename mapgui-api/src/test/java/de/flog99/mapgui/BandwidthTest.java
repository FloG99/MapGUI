package de.flog99.mapgui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandwidthTest {

    private long second = 1_000_000;

    private Bandwidth meter() {
        return new Bandwidth(() -> second);
    }

    /** A meter nothing has been given must read nothing, not a stale bucket from second nought. */
    @Test
    void anUntouchedMeterReadsNothing() {
        assertEquals(0, meter().perSecond());
    }

    /**
     * The second still being filled is left out, or every reading would be a fraction of the truth and
     * a busy server would look idle.
     */
    @Test
    void theSecondInProgressIsNotCountedYet() {
        Bandwidth meter = meter();
        meter.add(4096);

        assertEquals(0, meter.perSecond(), "still mid-second");
        second++;
        assertEquals(4096, meter.perSecond());
    }

    @Test
    void steadyTrafficAveragesToTheRate() {
        Bandwidth meter = meter();
        for (int i = 0; i < 4; i++) {
            meter.add(1000);
            second++;
        }
        assertEquals(1000, meter.perSecond());
    }

    /** The whole point of the window: traffic that stopped has to stop being reported. */
    @Test
    void trafficFallsOutOfTheWindow() {
        Bandwidth meter = meter();
        meter.add(50_000);
        second++;
        assertTrue(meter.perSecond() > 0);

        second += 10;
        assertEquals(0, meter.perSecond(), "a meter left alone goes quiet rather than remembering");
    }

    /** Buckets are reused by the second modulo the window, so a wrap must not add to a stale one. */
    @Test
    void reusingABucketAfterAFullLapStartsItFresh() {
        Bandwidth meter = meter();
        meter.add(9000);

        second += 5;
        meter.add(1000);
        second++;

        assertEquals(1000, meter.perSecond(), "the old 9000 must not still be in that bucket");
    }

    @Test
    void ratesAreDescribedInBothUnits() {
        assertTrue(Bandwidth.describe(640 * 1024).contains("KB/s"));
        assertTrue(Bandwidth.describe(640 * 1024).contains("Mbit/s"));
        assertTrue(Bandwidth.describe(4 * 1024 * 1024).contains("MB/s"));
    }
}
