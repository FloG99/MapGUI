package de.flog99.mapgui.plugin.camera;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureBudgetTest {

    private static final long MS = 1_000_000;
    private static final long TICK = 50 * MS;

    /** What one capture costs the main thread in these tests, so a budget of 1 ms/t buys 20 frames a second. */
    private static final long FRAME_COST = MS;

    private long now = TimeUnit.SECONDS.toNanos(1000);

    private CaptureBudget budget(double millisPerTick, int maxFps) {
        return new CaptureBudget(millisPerTick, maxFps, () -> now);
    }

    /**
     * Runs a second of ticks with every viewer asking for a frame every tick, and reports how many each got.
     *
     * <p>Through the real clock and the real interval rather than by reading the allocation out, since what a
     * viewer actually receives is the only number anybody cares about.
     */
    private List<Integer> framesInOneSecond(CaptureBudget budget, List<UUID> viewers) {
        List<Integer> taken = new ArrayList<>();
        for (int i = 0; i < viewers.size(); i++) taken.add(0);

        for (int tick = 0; tick < 20; tick++) {
            for (int i = 0; i < viewers.size(); i++) {
                if (budget.readyForFrame(viewers.get(i))) {
                    taken.set(i, taken.get(i) + 1);
                    budget.spent(viewers.get(i), FRAME_COST);
                }
            }
            now += TICK;
        }
        return taken;
    }

    private List<UUID> viewers(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add(UUID.randomUUID());

        return ids;
    }

    /**
     * The whole point, at the numbers it was specified with. One millisecond a tick is 20 ms a second, a frame
     * costs one of them, so the budget buys 20 frames a second across everybody - and a ceiling of 10 means the
     * first two viewers do not have to share.
     */
    @Test
    void theBudgetIsSpentToTheCeilingAndThenSharedOut() {
        assertEquals(List.of(10), settled(1, 10));
        assertEquals(List.of(10, 10), settled(2, 10));
        assertEquals(List.of(7, 7, 7), settled(3, 10));
        assertEquals(List.of(5, 5, 5), settled(4, 10).subList(0, 3));
    }

    /** Runs long enough for the measured cost to have replaced the assumed one, then reports the last second. */
    private List<Integer> settled(int count, int maxFps) {
        CaptureBudget budget = budget(1.0, maxFps);
        List<UUID> ids = viewers(count);
        framesInOneSecond(budget, ids);

        return framesInOneSecond(budget, ids);
    }

    /** Four viewers get a fifth of a second's worth each. Rounding lands where the interval falls, so it is 5 either way. */
    @Test
    void aFourthViewerSlowsTheOtherThreeRatherThanCostingMore() {
        List<Integer> four = settled(4, 10);
        assertEquals(4, four.size());
        for (int frames : four) {
            assertTrue(frames >= 4 && frames <= 6, "expected about 5 frames, got " + frames);
        }
    }

    /** A ceiling with no budget behind it is still a ceiling, or "no budget" would mean "no limit at all". */
    @Test
    void theCeilingHoldsWithNoBudget() {
        assertEquals(List.of(10, 10, 10, 10), settled4WithNoBudget());
    }

    private List<Integer> settled4WithNoBudget() {
        CaptureBudget budget = budget(0, 10);
        List<UUID> ids = viewers(4);
        framesInOneSecond(budget, ids);

        return framesInOneSecond(budget, ids);
    }

    /** A budget with no ceiling gives it all to whoever is there, which is what having no ceiling means. */
    @Test
    void oneViewerWithNoCeilingTakesTheWholeBudget() {
        CaptureBudget budget = budget(1.0, 0);
        List<UUID> ids = viewers(1);
        framesInOneSecond(budget, ids);

        // 20 ms of budget a second at a millisecond a frame, and 20 ticks to ask in.
        assertEquals(List.of(20), framesInOneSecond(budget, ids));
    }

    /**
     * A cheap view that hits the ceiling hands back what it does not need, so the expensive one gets more than an
     * even split. An even split would have given the expensive one 10 ms a second and 2.5 frames.
     */
    @Test
    void whatACheapViewCannotUseGoesToAnExpensiveOne() {
        CaptureBudget budget = budget(1.0, 10);
        UUID cheap = UUID.randomUUID();
        UUID dear = UUID.randomUUID();

        // Half a millisecond against four, so the cheap one needs 5 ms a second of its 20 and leaves 15. Long
        // enough that the expensive one has been measured rather than assumed - it only gets four frames a second
        // to be measured by, so it is the slower of the two to settle.
        for (int tick = 0; tick < 200; tick++) {
            if (budget.readyForFrame(cheap)) budget.spent(cheap, MS / 2);
            if (budget.readyForFrame(dear)) budget.spent(dear, 4 * MS);
            now += TICK;
        }

        CaptureBudget.Live live = budget.live();
        assertEquals(2, live.viewers());
        assertEquals(10, live.fastestFps(), 0.01);
        assertEquals(15.0 / 4, live.slowestFps(), 0.05);
    }

    /**
     * A view that stops asking stops being divided by. This is what lets the whole thing need no opening and no
     * closing: a plugin cannot leak a viewfinder, and a player who logs out takes their share with them.
     */
    @Test
    void aViewerWhoStopsAskingStopsBeingCountedFor() {
        CaptureBudget budget = budget(1.0, 10);
        List<UUID> ids = viewers(4);
        framesInOneSecond(budget, ids);

        assertEquals(4, budget.live().viewers());

        // Only the first goes on asking, and after a second of silence the other three are gone.
        for (int tick = 0; tick < 40; tick++) {
            if (budget.readyForFrame(ids.getFirst())) budget.spent(ids.getFirst(), FRAME_COST);
            now += TICK;
        }

        assertEquals(1, budget.live().viewers());
        assertEquals(10, budget.live().fastestFps(), 0.01);
    }

    @Test
    void nobodyLookingIsReportedAsNothingRatherThanAsZeroViewers() {
        assertNull(budget(1.0, 10).live());
    }

    /** Asking more often than the rate allows does not earn more frames, or the pacing would be advice only. */
    @Test
    void askingEveryTickDoesNotBeatTheRate() {
        CaptureBudget budget = budget(1.0, 5);
        UUID one = UUID.randomUUID();

        int taken = 0;
        for (int tick = 0; tick < 20; tick++) {
            for (int ask = 0; ask < 10; ask++) {
                if (budget.readyForFrame(one)) {
                    taken++;
                    budget.spent(one, FRAME_COST);
                }
            }
            now += TICK;
        }
        assertEquals(5, taken);
    }
}
