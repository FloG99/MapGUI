package de.flog99.mapgui.camera;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The derived readings, which are here rather than in every consumer because each of them was being worked out
 * again - with its own idea of how near the ceiling counts as on it.
 */
class CameraStatsTest {

    private static final CameraStats.Blocks BLOCKS = new CameraStats.Blocks(152, 78, 6, 24);

    private static CameraStats stats(double budgetMillisPerTick, int fpsCeiling, CameraStats.Live live) {
        return new CameraStats(13, 3.2, 0, 0.34, 0.68, 4.1, 6.8, 6.0, 0.5, 0.3, 184.0, BLOCKS, 8, 62, 4, 0, 0, 0,
                budgetMillisPerTick, fpsCeiling, null, List.of(), live);
    }

    @Test
    void nothingOpenIsItsOwnAnswerRatherThanNoLimit() {
        assertEquals(CameraStats.Bound.NOTHING_OPEN, stats(1.0, 10, CameraStats.Live.NONE).bound());
    }

    /** Sitting on the ceiling, allowing for a rate that was arrived at by division. */
    @Test
    void aViewAtTheCeilingIsHeldByTheCeiling() {
        CameraStats.Live live = new CameraStats.Live(1, 9.99, 9.99, 0.5);

        assertEquals(CameraStats.Bound.FPS_CEILING, stats(1.0, 10, live).bound());
    }

    /**
     * The distinction the whole enum exists for: three frames a second under a ten frame ceiling is a budget that
     * ran out, and three under a three frame ceiling is a setting somebody chose.
     */
    @Test
    void aViewShortOfTheCeilingIsHeldByTheBudget() {
        CameraStats.Live live = new CameraStats.Live(1, 3.0, 3.0, 1.0);

        assertEquals(CameraStats.Bound.TICK_BUDGET, stats(1.0, 10, live).bound());
        assertEquals(CameraStats.Bound.FPS_CEILING, stats(1.0, 3, live).bound());
    }

    @Test
    void neitherSettingOnIsUnlimited() {
        CameraStats.Live live = new CameraStats.Live(1, 30, 30, 4.0);

        assertEquals(CameraStats.Bound.UNLIMITED, stats(0, 0, live).bound());
    }

    /** So that reading the viewer count never needs a null check first. */
    @Test
    void aMissingLiveReadingBecomesNoViewers() {
        assertSame(CameraStats.Live.NONE, stats(1.0, 10, null).live());
    }
}
