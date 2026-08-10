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
 * @param copyMillisEach     of that, what copying the world cost; {@code mobMillisEach} what gathering what is
 *                           standing in it cost; {@code blockEntityMillisEach} what gathering what is bolted to it
 *                           cost. Three, because they are three different fixes: a big copy wants a shorter
 *                           {@code max-distance} or chunk reuse, a big mob gather wants fewer entities in frame, and
 *                           a big block-entity gather is chests and signs rather than anything alive
 * @param traceMillisEach    how long one capture takes off the main thread, which is what decides how many a second
 *                           this machine can keep up with
 * @param unpacedPerSecond   captures that did not ask {@link Camera#readyForFrame} first, so no live budget was
 *                           applied to them. Worth watching: an admin who set a budget and is being ignored has no
 *                           other way to tell
 * @param copy               what the copy went through, for working out why it cost what it did
 * @param mobsEach           how many mob snapshots one capture built, and {@code blockEntitiesEach} how many
 *                           block-entity ones. Against the times above, these are what say whether a gather is slow
 *                           because there is a lot in shot or because each one is expensive - two problems with
 *                           completely different answers
 * @param mobsReusedPercent  0 to 100, of the mobs a capture drew: how many came from a shape an earlier capture had
 *                           already built. Costs nothing in a mob's position, which is always read fresh - only in
 *                           how quickly it is redrawn after it puts something on. Low on a moving camera is normal
 * @param queued             captures copied and now waiting for a thread to trace them
 * @param dropped            captures turned away because the queue was full. Not a failure - nothing broke, the
 *                           machine was asked for more than it could draw - and the caller was handed a null shot
 * @param liveMaxMillisPerTick {@code camera.live.max-ms-per-tick}, or 0 for no budget
 * @param liveFpsCeiling     {@code camera.live.max-fps}, or 0 for no ceiling
 * @param lastFailure        the last capture that threw, or null. Kept past the window, since a camera that fails
 *                           every time looks from outside exactly like one nothing is using
 * @param callers            which plugin asked, busiest first
 * @param live               what the paced live views are getting, or null when nobody has one open
 */
public record CameraStats(
        int captures,
        double capturesPerSecond,
        double unpacedPerSecond,
        double mainMillisPerTick,
        double tickPercent,
        double worstMainMillis,
        double mainMillisEach,
        double copyMillisEach,
        double mobMillisEach,
        double blockEntityMillisEach,
        double traceMillisEach,
        Copy copy,
        double mobsEach,
        double mobsReusedPercent,
        double blockEntitiesEach,
        int queued,
        int dropped,
        int failed,
        double liveMaxMillisPerTick,
        int liveFpsCeiling,
        Failure lastFailure,
        List<Caller> callers,
        Live live) {

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
     * What one capture's copy went through, averaged over the window. <b>Chunks</b> is the driver, and the cost is
     * very nearly linear in it; <b>reused</b> says whether a live view is getting the cache or re-copying the world
     * every frame. <b>Filled against total sections</b> bounds what smarter copying could win, since a chunk is
     * copied whole but a section of pure air is nearly free.
     *
     * @param reusedPercent 0 to 100, of the chunks this capture wanted
     */
    public record Copy(double chunksEach, double reusedPercent, double filledSectionsEach, double sectionsEach) {
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
    }

    /** The two gathers together, which is what shows against the copy as the other half of the tick. */
    public double entityMillisEach() {
        return mobMillisEach + blockEntityMillisEach;
    }

    /** Nothing captured, nothing failed and nothing turned away, which is different from everything being cheap. */
    public boolean idle() {
        return captures == 0 && failed == 0 && dropped == 0;
    }
}
