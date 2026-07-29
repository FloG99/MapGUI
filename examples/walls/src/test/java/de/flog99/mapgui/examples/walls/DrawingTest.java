package de.flog99.mapgui.examples.walls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawingTest {

    private static final byte RED = 1;
    private static final byte BLUE = 2;

    private final Drawing drawing = new Drawing();

    @Test
    void aDotIsRoundAndCenteredWhereItWasAsked() {
        drawing.dot(50, 50, 3, RED);

        assertEquals(RED, drawing.at(50, 50));
        assertEquals(RED, drawing.at(53, 50));
        assertEquals(0, drawing.at(54, 50), "past the radius");
        assertEquals(0, drawing.at(53, 53), "the corner of the square, so it is a disc not a box");
    }

    /**
     * The nub. An exact circle reaches its full radius only straight up, down and across, so its extreme
     * rows are a single pixel and every brush size grows a spike - which is obvious on a 128 pixel map.
     */
    @Test
    void noBrushSizeGrowsASpike() {
        for (int radius = 1; radius <= 10; radius++) {
            // Spaced so ten brushes, the widest 21 pixels, sit side by side without touching.
            int centerX = radius * 22 + 12;
            int centerY = 100;
            drawing.dot(centerX, centerY, radius, RED);

            int width = 0;
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                if (drawing.at(x, centerY - radius) == RED) {
                    width++;
                }
            }
            assertTrue(width >= 3, "radius " + radius + " ends in a " + width + " pixel spike");
        }
    }

    @Test
    void drawingOffTheEdgeIsIgnoredRatherThanWrapping() {
        drawing.dot(0, 0, 4, RED);

        assertEquals(RED, drawing.at(0, 0));
        assertEquals(0, drawing.at(Drawing.SIZE - 1, 0), "wrapped onto the row above");
    }

    /** The point of a line: no gaps, however shallow the slope. */
    @Test
    void aLineIsSolidEndToEnd() {
        drawing.line(10, 10, 90, 30, 1, RED);

        for (int x = 10; x <= 90; x++) {
            boolean covered = false;
            for (int y = 5; y <= 35 && !covered; y++) covered = drawing.at(x, y) == RED;
            assertTrue(covered, "nothing drawn in column " + x);
        }
    }

    @Test
    void aLineReachesBothEnds() {
        drawing.line(20, 20, 60, 60, 1, RED);

        assertEquals(RED, drawing.at(20, 20));
        assertEquals(RED, drawing.at(60, 60));
    }

    /**
     * A curve has to be solid too, and this is the case that catches sampling alone: a control point far
     * off to one side makes the middle of the curve move much faster than its ends.
     */
    @Test
    void aCurveIsSolidEvenWhereItBendsHardest() {
        drawing.curve(20, 100, 100, 20, 180, 100, 1, RED);

        for (int x = 20; x <= 180; x++) {
            boolean covered = false;
            for (int y = 0; y <= 120 && !covered; y++) covered = drawing.at(x, y) == RED;
            assertTrue(covered, "nothing drawn in column " + x);
        }
    }

    /** It passes through its ends but bends toward the control point rather than through it. */
    @Test
    void aCurveEndsWhereAskedAndBendsTowardTheControl() {
        drawing.curve(20, 100, 100, 20, 180, 100, 1, RED);

        assertEquals(RED, drawing.at(20, 100));
        assertEquals(RED, drawing.at(180, 100));
        assertEquals(0, drawing.at(100, 20), "went through the control point instead of near it");
        assertEquals(RED, drawing.at(100, 60), "the halfway point of a quadratic");
    }

    @Test
    void fillingSpreadsThroughOneColorAndStopsAtAnother() {
        drawing.line(50, 0, 50, Drawing.SIZE - 1, 1, RED);
        drawing.flood(10, 10, BLUE);

        assertEquals(BLUE, drawing.at(10, 10));
        assertEquals(BLUE, drawing.at(47, Drawing.SIZE - 1), "up against the wall");
        assertEquals(RED, drawing.at(50, 10), "the wall itself");
        assertEquals(0, drawing.at(60, 10), "leaked through the wall");
    }

    /** Four-way, so paint does not escape through the gap a one pixel diagonal leaves. */
    @Test
    void fillingDoesNotLeakThroughADiagonal() {
        for (int step = 0; step < Drawing.SIZE; step++) drawing.dot(step, step, 0, RED);
        drawing.flood(Drawing.SIZE - 1, 0, BLUE);

        assertEquals(BLUE, drawing.at(Drawing.SIZE - 1, 0), "above the diagonal");
        assertEquals(0, drawing.at(0, Drawing.SIZE - 1), "leaked across the diagonal");
    }

    @Test
    void fillingTheSameColorChangesNothing() {
        drawing.dot(20, 20, 4, RED);
        drawing.flood(20, 20, RED);

        assertEquals(RED, drawing.at(20, 20));
    }

    @Test
    void fillingRepaintsAShapeWithoutTouchingWhatIsAroundIt() {
        drawing.dot(60, 60, 10, RED);
        drawing.flood(60, 60, BLUE);

        assertEquals(BLUE, drawing.at(60, 60));
        assertEquals(BLUE, drawing.at(69, 60), "the edge of the shape");
        assertEquals(0, drawing.at(80, 60), "outside it");
    }

    @Test
    void clearingLeavesNothingBehind() {
        drawing.line(0, 0, 100, 100, 3, RED);
        drawing.clear();

        assertEquals(0, drawing.at(50, 50));
    }

    /** The canvas is the whole wall, so its last pixel has to be one you can draw on. */
    @Test
    void theFarCornerCanBeDrawnOn() {
        drawing.dot(Drawing.SIZE - 1, Drawing.SIZE - 1, 0, RED);

        assertEquals(RED, drawing.at(Drawing.SIZE - 1, Drawing.SIZE - 1));
    }
}
