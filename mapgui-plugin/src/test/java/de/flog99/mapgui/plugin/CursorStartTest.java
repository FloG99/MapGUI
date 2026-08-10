package de.flog99.mapgui.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the cursor appears, which is the middle until the head runs out of room to reach an edge from there.
 *
 * <p>Unclamped only: the cursor follows the pitch as a delta, and the pitch stops at 90 either way. Starting mid map
 * while looking straight down leaves nothing below it, so half the screen cannot be reached at all.
 */
class CursorStartTest {

    private static final int HEIGHT = 128;
    private static final float MIN = 45;
    private static final float MAX = 90;

    private static double startAt(float pitch) {
        return PlayerSession.startRow(HEIGHT, MIN, MAX, pitch);
    }

    /** How many rows the head can still drag the cursor down from where it started. */
    private static double reachBelow(float pitch) {
        double perDegree = HEIGHT / (double) (MAX - MIN);
        return (90 - pitch) * perDegree;
    }

    private static double reachAbove(float pitch) {
        double perDegree = HEIGHT / (double) (MAX - MIN);
        return (pitch + 90) * perDegree;
    }

    @Test
    void lookingLevelStartsInTheMiddle() {
        assertEquals(HEIGHT / 2.0, startAt(0), 0.001);
        assertEquals(HEIGHT / 2.0, startAt(30), 0.001);
        assertEquals(HEIGHT / 2.0, startAt(-30), 0.001);
    }

    /** Straight down: there is no pitch left below, so the cursor has to start at the bottom or be stuck. */
    @Test
    void lookingStraightDownStartsAtTheBottom() {
        assertEquals(HEIGHT - 1, startAt(90), 0.001);
    }

    @Test
    void lookingStraightUpStartsAtTheTop() {
        assertEquals(0, startAt(-90), 0.001);
    }

    /**
     * The point of the whole thing, at every pitch a player can hold: wherever the cursor starts, the head has the
     * travel left to drag it to both edges.
     */
    @Test
    void everyRowIsReachableFromWhereverItStarts() {
        for (float pitch = -90; pitch <= 90; pitch += 2.5f) {
            double row = startAt(pitch);

            assertTrue(row >= 0 && row <= HEIGHT - 1, "at pitch " + pitch + " the cursor starts off the map: " + row);
            assertTrue(row + reachBelow(pitch) >= HEIGHT - 1 - 0.001,
                    "at pitch " + pitch + " the bottom row cannot be reached from " + row);
            assertTrue(row - reachAbove(pitch) <= 0.001,
                    "at pitch " + pitch + " the top row cannot be reached from " + row);
        }
    }

    /** It gives way gradually rather than at a threshold, so nothing jumps as the player tips further over. */
    @Test
    void itSlidesTowardTheEdgeRatherThanSnapping() {
        double previous = -1;
        for (float pitch : new float[]{68, 72, 76, 80, 85, 90}) {
            double row = startAt(pitch);
            assertTrue(row > previous, "at pitch " + pitch + " the start should be lower than before");
            previous = row;
        }
    }

    /**
     * A wider pitch range moves the cursor more slowly per degree, so crossing the map costs more of the head and it
     * gives way at a pitch a narrow range still has room at.
     */
    @Test
    void aRangeThatCrossesTheMapMoreSlowlyGivesWaySooner() {
        assertEquals(HEIGHT / 2.0, startAt(60), 0.001);
        assertTrue(PlayerSession.startRow(HEIGHT, 0, 90, 60) > HEIGHT / 2.0,
                "over 90 degrees the map takes 90 degrees to cross, and only 30 are left below");
    }
}
