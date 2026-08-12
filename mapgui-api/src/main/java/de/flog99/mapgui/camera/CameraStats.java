package de.flog99.mapgui.camera;

import java.util.List;

/**
 * What the camera has been doing lately, as numbers rather than as a message.
 *
 * <p>The same reading {@code /mapgui camera performance} prints - that command has no private access to any of this,
 * which is deliberate: a built-in command working from a narrower view than a plugin can get is how an API ends up
 * missing the field somebody needed. Take it and print it however your own debugging command wants to.
 *
 * <p>A rolling few seconds rather than a total since startup, because "is this costing my server anything" is a
 * question about now. Everything is a snapshot taken together, so the numbers all describe the same moment.
 *
 * <p>Times are milliseconds, and the main-thread ones are <b>per tick</b> - the unit a Minecraft server is read in,
 * and the unit {@code camera.live.max-ms-per-tick} is written in, so the two can be held against each other.
 *
 * @param captures           captures counted in the window, which is what the rates are made of
 * @param mainMillisPerTick  what copying the world for them took out of each tick. The only part of a capture that
 *                           can cost the server its TPS - the trace runs on a pool and cannot
 * @param tickPercent        the same figure as a share of the 50 ms a tick has
 * @param worstMainMillis    the most any single capture held the tick, since one long copy is a stutter a player
 *                           saw and an average hides it
 * @param mainMillisEach     what one capture takes out of a tick. Against the budget this is what decides a live
 *                           view's frame rate, so it is the number to look at when one is slower than expected
 * @param blockMillisEach    of that, what copying the world's blocks cost; {@code entityMillisEach} what gathering
 *                           what is standing in it cost; {@code blockEntityMillisEach} what gathering what is bolted
 *                           to it cost. Three, because they are three different fixes: a big copy wants a shorter
 *                           {@code max-distance} or chunk reuse, a big entity gather wants fewer things in frame, and
 *                           a big block-entity gather is chests and signs rather than anything alive
 * @param traceMillisEach    how long one capture takes off the main thread, which is what decides how many a second
 *                           this machine can keep up with
 * @param unpacedPerSecond   captures that did not ask {@link Camera#readyForFrame} first, so no live budget was
 *                           applied to them. Worth watching: an admin who set a budget and is being ignored has no
 *                           other way to tell. A {@link CameraFeed} is always paced
 * @param blocks             what copying the blocks went through, for working out why it cost what it did
 * @param entitiesEach       how many entity snapshots one capture built, and {@code blockEntitiesEach} how many
 *                           block-entity ones. Against the times above, these are what say whether a gather is slow
 *                           because there is a lot in shot or because each one is expensive - two problems with
 *                           completely different answers
 * @param entitiesReusedPercent 0 to 100, of the entities a capture drew: how many came from a shape an earlier
 *                           capture had already built. Costs nothing in an entity's position, which is always read
 *                           fresh - only in how quickly it is redrawn after it puts something on. Low on a moving
 *                           camera is normal
 * @param queued             captures copied and now waiting for a thread to trace them
 * @param dropped            captures turned away because the queue was full. Not a failure - nothing broke, the
 *                           machine was asked for more than it could draw - and the caller was handed a null shot
 * @param liveMaxMillisPerTick {@code camera.live.max-ms-per-tick}, or 0 for no budget
 * @param liveFpsCeiling     {@code camera.live.max-fps}, or 0 for no ceiling
 * @param lastFailure        the last capture that threw, or null. Kept past the window, since a camera that fails
 *                           every time looks from outside exactly like one nothing is using
 * @param callers            which plugin asked, busiest first
 * @param live               what the paced live views are getting. Never null - {@link Live#viewers()} is 0 when
 *                           nobody has one open
 */
public record CameraStats(
        int captures,
        double capturesPerSecond,
        double unpacedPerSecond,
        double mainMillisPerTick,
        double tickPercent,
        double worstMainMillis,
        double mainMillisEach,
        double blockMillisEach,
        double entityMillisEach,
        double blockEntityMillisEach,
        double traceMillisEach,
        Blocks blocks,
        double entitiesEach,
        double entitiesReusedPercent,
        double blockEntitiesEach,
        int queued,
        int dropped,
        int failed,
        double liveMaxMillisPerTick,
        int liveFpsCeiling,
        Failure lastFailure,
        List<Caller> callers,
        Live live) {

    /** How near the ceiling counts as on it. The rate comes out of a division, so 10 fps reads as 9.999 as often. */
    private static final double FPS_SLACK = 0.05;

    public CameraStats {
        live = live == null ? Live.NONE : live;
    }

    /**
     * A plugin and how often it is capturing.
     *
     * <p>Worked out from the stack at the moment {@code capture} was called rather than from anything declared, so
     * it is a label and not a contract - but it is the actionable half of a rate, since turning a camera down means
     * turning down whatever asked for it.
     */
    public record Caller(String plugin, double capturesPerSecond) {
    }

    /**
     * What one capture's copy of the world went through, averaged over the window. <b>Chunks</b> is the driver, and
     * the cost is very nearly linear in it; <b>reused</b> says whether a live view is getting the cache or re-copying
     * the world every frame. <b>Filled against total sections</b> bounds what smarter copying could win, since a
     * chunk is copied whole but a section of pure air is nearly free.
     *
     * @param reusedPercent 0 to 100, of the chunks this capture wanted
     */
    public record Blocks(double chunksEach, double reusedPercent, double filledSectionsEach, double sectionsEach) {
    }

    /**
     * @param reason what was thrown, as text - the stack is in the console
     * @param at     wall-clock millis, since this is read minutes later and a nanosecond reading means nothing
     *               across that
     */
    public record Failure(String plugin, String reason, long at) {
    }

    /**
     * What {@link Camera#readyForFrame} is handing out.
     *
     * @param usedMillisPerTick what the rates handed out add up to, which is the figure
     *                          {@link CameraStats#liveMaxMillisPerTick} only means anything against - well under it
     *                          means the fps ceiling is what is binding, so the budget is not the number to change
     */
    public record Live(int viewers, double slowestFps, double fastestFps, double usedMillisPerTick) {

        /** Nobody watching, which is a state rather than an absence - hence this rather than a null. */
        public static final Live NONE = new Live(0, 0, 0, 0);

        /** Whether every view is being allowed the same rate, which is the usual case and reads as one figure. */
        public boolean even() {
            return slowestFps >= fastestFps - FPS_SLACK;
        }
    }

    /**
     * Why the live views are running at the rate they are, which is the one thing a rate cannot say by itself.
     *
     * <p>Three frames a second under a ten frame ceiling is a budget that ran out; three under a three frame ceiling
     * is a setting somebody chose. They call for opposite actions and look identical from the number alone.
     */
    public enum Bound {

        /** Nothing to hold back - no live view is open. */
        NOTHING_OPEN,

        /** Sitting on {@code camera.live.max-fps}. Raise that to go faster, and expect it to cost the tick. */
        FPS_CEILING,

        /**
         * Held by {@code camera.live.max-ms-per-tick}, so the views are as fast as the time allows.
         *
         * <p>The way to more frames is more budget, or cheaper captures - a smaller size, a shorter
         * {@code max-distance}, more reuse.
         */
        TICK_BUDGET,

        /** Neither setting is on, so views run as fast as they are asked for. */
        UNLIMITED
    }

    /** Which of the two settings is the binding one - see {@link Bound}. */
    public Bound bound() {
        if (live.viewers() == 0) return Bound.NOTHING_OPEN;
        if (liveFpsCeiling > 0 && live.fastestFps() >= liveFpsCeiling - FPS_SLACK) return Bound.FPS_CEILING;
        if (liveMaxMillisPerTick > 0) return Bound.TICK_BUDGET;

        return Bound.UNLIMITED;
    }

    /** The two gathers together, which is what shows against the block copy as the other half of the tick. */
    public double gatherMillisEach() {
        return entityMillisEach + blockEntityMillisEach;
    }

    /** Nothing captured, nothing failed and nothing turned away, which is different from everything being cheap. */
    public boolean idle() {
        return captures == 0 && failed == 0 && dropped == 0;
    }
}
