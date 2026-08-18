package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.camera.CameraStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report reads {@link CameraStats} and nothing else, so it can be tested by handing it one.
 *
 * <p>Which is the point of these: the boundary between what the camera counts and what an admin reads is where a
 * unit gets lost, and it has already carried nanoseconds, milliseconds, per-second and per-tick. None of that would
 * fail a compile.
 */
class CameraReportTest {

    private static String text(List<Component> lines) {
        StringBuilder all = new StringBuilder();
        for (Component line : lines) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    private static final CameraStats.Blocks BLOCKS = new CameraStats.Blocks(152, 78, 6, 24);

    private static CameraStats stats() {
        return new CameraStats(13, 3.2, 0, 0.34, 0.68, 4.1, 6.8, 6.0, 0.5, 0.3, 184.0, BLOCKS, 8, 62, 4, 0, 0, 0, 1.0, 10, null,
                List.of(new CameraStats.Caller("PhotoBooth", 3.0)),
                new CameraStats.Live(3, 6.7, 6.7, 0.92));
    }

    /**
     * The whole reason the figure is per tick: it is the unit a server is read in and the unit the budget is
     * written in. Per second it would read 6.8, which is the same cost and a twentyfold difference on the page.
     */
    @Test
    void mainThreadCostIsPrintedPerTick() {
        String out = text(CameraReport.lines(stats()));

        assertTrue(out.contains("0.34ms/t"), out);
        assertFalse(out.contains("ms/s"), "per-second would be the same cost read twenty times too high: " + out);
    }

    /** Under a millisecond a single decimal rounds a real cost to nothing, and nothing reads as free. */
    @Test
    void aSmallCostKeepsTwoDecimalsRatherThanRoundingToNought() {
        CameraStats small = new CameraStats(1, 0.25, 0, 0.04, 0.08, 1.6, 1.6, 1.4, 0.1, 0.1, 20, BLOCKS, 1, 0, 1, 0, 0, 0, 1.0, 10, null, List.of(), CameraStats.Live.NONE);

        assertTrue(text(CameraReport.lines(small)).contains("0.04ms/t"), text(CameraReport.lines(small)));
    }

    /** The used-of-allowed pair is what says which of the two settings is the binding one. */
    @Test
    void liveViewsAreShownAgainstTheLimitThatDecidedThem() {
        String out = text(CameraReport.lines(stats()));

        assertTrue(out.contains("3 viewers at 6.7 fps"), out);
        assertTrue(out.contains("0.92 of 1.0ms/t"), out);
        assertTrue(out.contains("10 fps cap"), out);
    }

    /**
     * A budget an admin set and nothing is asking for is the one state that used to look like agreement: no live
     * views listed, beside a camera capturing twenty times a second.
     */
    @Test
    void capturesTakenWithoutAskingAreCalledOutWhenABudgetExists() {
        CameraStats unpaced = new CameraStats(80, 20.0, 20.0, 2.0, 4.0, 5.0, 2.0, 1.8, 0.1, 0.1, 30, BLOCKS, 1, 0, 1, 0, 0, 0, 1.0, 10, null, List.of(), CameraStats.Live.NONE);
        String out = text(CameraReport.lines(unpaced));

        assertTrue(out.contains("Unpaced"), out);
        assertTrue(out.contains("20.0/s"), out);
    }

    /** With no budget set there is nothing for it to be a warning about, so it would only be noise. */
    @Test
    void unpacedCapturesAreNotMentionedWhenNothingWasLimited() {
        CameraStats loose = new CameraStats(80, 20.0, 20.0, 2.0, 4.0, 5.0, 2.0, 1.8, 0.1, 0.1, 30, BLOCKS, 1, 0, 1, 0, 0, 0, 0, 0, null, List.of(), null);

        assertFalse(text(CameraReport.lines(loose)).contains("Unpaced"));
    }

    /** Turned away is not failed, and the words have to differ or an admin goes looking for a stack trace. */
    @Test
    void capturesTurnedAwayReadAsCapacityRatherThanAsBreakage() {
        CameraStats over = new CameraStats(40, 10.0, 10.0, 2.0, 4.0, 5.0, 4.0, 3.5, 0.3, 0.2, 30, BLOCKS, 2, 0, 1, 3, 12, 0, 1.0, 10, null, List.of(), CameraStats.Live.NONE);
        String out = text(CameraReport.lines(over));

        assertTrue(out.contains("Turned away"), out);
        assertFalse(out.contains("Failed"), "nothing threw, so nothing failed: " + out);
    }

    /** An empty queue is the normal state, and a count of nothing waiting is a line an eye learns to skip. */
    @Test
    void theQueueIsOnlyMentionedWhenSomethingIsInIt() {
        assertFalse(text(CameraReport.lines(stats())).contains("waiting"), "nothing is queued in the sample");

        CameraStats behind = new CameraStats(40, 10.0, 0, 2.0, 4.0, 5.0, 4.0, 3.5, 0.3, 0.2, 120, BLOCKS, 2, 0, 1, 3, 0, 0, 1.0, 10, null, List.of(), CameraStats.Live.NONE);
        assertTrue(text(CameraReport.lines(behind)).contains("3 waiting"), text(CameraReport.lines(behind)));
    }

    /**
     * The trace time is printed whether or not anything is queued, because for a live view it is the latency.
     *
     * <p>It used to be gated on the queue having something in it at the moment somebody asked, which is almost never. So
     * a camera taking nearly two hundred milliseconds a frame printed four figures saying it was free and left that one
     * out - and an admin comparing a mirror that visibly lagged against "0.34ms/t" was reading the only number in the
     * report that could not have been the cause.
     */
    @Test
    void theTraceTimeIsPrintedWithNothingQueued() {
        assertEquals(0, stats().queued(), "the whole point of this one is that nothing is waiting");

        String out = text(CameraReport.lines(stats()));
        assertTrue(out.contains("184.0ms a frame"), out);
        assertTrue(out.contains("off the main thread"), out);
    }

    /**
     * Several live views are told that they wait for each other, since one capture is traced at a time.
     *
     * <p>The form the question arrives in is "two mirrors got slower than one and nothing on the tick moved", and the
     * answer is on no other line: a capture is already spread across every core, so a second one queues behind the first
     * rather than sharing the machine.
     */
    @Test
    void severalLiveViewsAreToldTheyWaitForEachOther() {
        assertTrue(text(CameraReport.lines(stats())).contains("3 views wait for each other"),
                text(CameraReport.lines(stats())));
    }

    /** One view has nobody to take turns with, so saying so would only be a sentence to skip. */
    @Test
    void aLoneLiveViewIsNotToldAboutTakingTurns() {
        CameraStats alone = new CameraStats(13, 3.2, 0, 0.34, 0.68, 4.1, 6.8, 6.0, 0.5, 0.3, 184.0, BLOCKS, 8, 62, 4, 0, 0, 0, 1.0, 10, null,
                List.of(new CameraStats.Caller("MapMirrors", 3.0)),
                new CameraStats.Live(1, 6.7, 6.7, 0.92));
        String out = text(CameraReport.lines(alone));

        assertTrue(out.contains("184.0ms a frame"), out);
        assertFalse(out.contains("wait for each other"), out);
    }

    /** Four zeroes would read as "cheap" when what is wrong is that captures are not happening at all. */
    @Test
    void aWindowOfNothingButFailuresSkipsTheCostLines() {
        CameraStats broken = new CameraStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BLOCKS, 0, 0, 0, 0, 0,3, 1.0, 10,
                new CameraStats.Failure("PhotoBooth", "java.lang.NullPointerException: nope", System.currentTimeMillis()),
                List.of(), null);
        String out = text(CameraReport.lines(broken));

        assertFalse(out.contains("Main thread"), out);
        assertTrue(out.contains("Failed  3"), out);
        assertTrue(out.contains("NullPointerException"), out);
    }

    /** A camera that fails every time and one nothing uses look the same from outside, so the failure outlives the window. */
    @Test
    void anIdleCameraStillReportsTheLastFailureHoweverOldItIs() {
        CameraStats quiet = new CameraStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BLOCKS, 0, 0, 0, 0, 0,0, 1.0, 10,
                new CameraStats.Failure("PhotoBooth", "java.lang.IllegalStateException: gone", 0),
                List.of(), null);
        String out = text(CameraReport.lines(quiet));

        assertTrue(out.contains("nothing captured"), out);
        assertTrue(out.contains("Last failure"), out);
    }

    /** /mapgui status only speaks up when something is wrong, and only while it is still recent. */
    @Test
    void troubleIsReportedOnlyForARecentFailure() {
        CameraStats fresh = new CameraStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BLOCKS, 0, 0, 0, 0, 0,1, 1.0, 10,
                new CameraStats.Failure("PhotoBooth", "boom", System.currentTimeMillis()), List.of(), null);
        CameraStats stale = new CameraStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BLOCKS, 0, 0, 0, 0, 0,1, 1.0, 10,
                new CameraStats.Failure("PhotoBooth", "boom", 0), List.of(), null);

        assertNotNull(CameraReport.trouble(fresh));
        assertNull(CameraReport.trouble(stale));
        assertNull(CameraReport.trouble(stats()), "a working camera is not something happening to a server");
    }

    /** The performance one-liner is for a report about cost, so a camera costing nothing has nothing to add to it. */
    @Test
    void theCostLineIsLeftOutWhenNothingIsCapturing() {
        assertNull(CameraReport.cost(new CameraStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, BLOCKS, 0, 0, 0, 0, 0,0, 1.0, 10, null, List.of(), CameraStats.Live.NONE)));
        assertEquals(true, PlainTextComponentSerializer.plainText()
                .serialize(CameraReport.cost(stats())).contains("0.34ms/t"));
    }

    /**
     * All three stages on one line, each with what it went through beside what it cost - because a slow stage is
     * either a lot of things or expensive things, and those two have opposite answers.
     */
    @Test
    void whatEachStageCostCarriesWhatItWentThrough() {
        String out = text(CameraReport.lines(stats()));

        assertTrue(out.contains("blocks 6.0ms (152 chunks, 78% reused)"), out);
        assertTrue(out.contains("entities 0.5ms (8, 62% reused)"), out);
        assertTrue(out.contains("tile entities 0.3ms (4)"), out);
    }

    /** Who is asking is the actionable half of a rate, so it goes on the line the rate is on. */
    @Test
    void theCallingPluginIsNamedBesideTheRate() {
        assertTrue(text(CameraReport.lines(stats())).contains("PhotoBooth"));
    }
}
