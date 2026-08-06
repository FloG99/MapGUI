package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallLoopTest {

    private static final WallLayout LAYOUT = WallLayout.anchoredAt(0, 64, 0, BlockFace.NORTH).resized(2, 1);

    /** A bar that grows with time, so each step has to come out different from the last. */
    private static WallContent growingBar(List<Long> painted) {
        return (painter, bounds, millis) -> {
            painted.add(millis);
            painter.fill(new Rect(0, 0, (int) Math.max(1, millis / 10), 20), Color.RED);
        };
    }

    @Test
    void everyStepIsPaintedOnceAtItsOwnPointInTheLoop() {
        List<Long> painted = new ArrayList<>();
        WallLoop loop = WallLoop.paint(LAYOUT, growingBar(painted), 4, 1000);

        assertEquals(4, loop.stepCount());
        assertEquals(List.of(0L, 250L, 500L, 750L), painted, "evenly spaced across the period");
    }

    @Test
    void stepsAreCappedSoAClientIsNotAskedToHoldTheWorld() {
        WallLoop loop = WallLoop.paint(LAYOUT, growingBar(new ArrayList<>()), 500, 5000);

        assertEquals(WallLoop.MAX_STEPS, loop.stepCount());
    }

    @Test
    void aStepIsAWholeFrameReadyToSend() {
        WallLoop loop = WallLoop.paint(LAYOUT, growingBar(new ArrayList<>()), 4, 1000);

        MapSurface first = loop.step(0);
        MapSurface last = loop.step(3);

        assertEquals(LAYOUT.pixelWidth(), first.width());
        assertEquals(LAYOUT.pixelHeight(), first.height());
        assertTrue(first.isDirty(), "painted and never sent, so all of it is still owed");
        assertNotEquals(first.get(50, 10), last.get(50, 10), "a later step is further through the animation");
    }

    @Test
    void oneStepIsAllowedAndIsJustAStillPicture() {
        WallLoop loop = WallLoop.paint(LAYOUT, growingBar(new ArrayList<>()), 1, 1000);

        assertEquals(1, loop.stepCount());
    }
}
