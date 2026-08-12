package de.flog99.mapgui.plugin.camera;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * How often a live view may take a frame, so that viewfinders cost the server what they are given and no more.
 *
 * <p>A still photograph is not this problem - one capture is one capture, and whoever pressed the shutter waited for
 * it. A viewfinder is: it wants every frame it can get, forever, and the answer to "how many is that" depends on how
 * many other people are pointing one at the same time. Only the server can see all of them, so only the server can
 * divide the time, which is why the arithmetic is here rather than in whichever plugin drew the screen.
 *
 * <p>Two numbers decide it. A <b>budget</b> in milliseconds per tick, which is main-thread time and therefore the only
 * kind a capture can take from the server, and an <b>fps ceiling</b>, because past some rate a viewfinder stops
 * looking any better and only costs more. Everything between them is spent: viewers get as many frames as the budget
 * affords and stop at the ceiling, so one viewer does not get twenty times the frames just because they are alone.
 *
 * <p>What one frame costs is measured rather than assumed, per viewer, because it genuinely differs - a 64-pixel
 * viewfinder pointed at a wall copies a fraction of what a 128-pixel one pointed across a valley does. The division
 * is therefore of time and not of frames: two viewers with different costs get the rates their own costs earn.
 */
final class CaptureBudget {

    /** Ticks in a second, which is what turns a per-tick budget into time a second holds. */
    private static final int TICKS_PER_SECOND = 20;

    /** What a first frame is assumed to cost when there is nothing measured to go on. Corrects itself within a second. */
    private static final long ASSUMED_NANOS = 1_000_000;

    /**
     * How long after its last question a viewer is still counted as one.
     *
     * <p>What makes this need no opening or closing: a screen that stops asking stops being divided by, so a plugin
     * cannot leak a viewfinder, and a player who logs out takes their share with them without anybody being told.
     */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * How much of a new measurement replaces the old one when a capture came in <b>dearer</b> than expected.
     *
     * <p>Gentle, because one expensive frame is usually a camera swung at a valley for a moment rather than a new
     * normal, and reacting to each of those would make the rate flutter.
     */
    private static final double SMOOTHING_UP = 0.25;

    /**
     * And when it came in <b>cheaper</b>. Much faster, and the asymmetry is the point.
     *
     * <p>The two errors are not each other's mirror. Guessing too <i>low</i> corrects itself at once: the next
     * capture is measured and says so. Guessing too <i>high</i> starves its own correction - a cost estimate that
     * is ten times the truth cuts the rate to a tenth, and a tenth of the frames is a tenth of the measurements
     * that would put it right, so the mistake outlives itself.
     *
     * <p>Which is exactly what a cold start looked like. The first capture copies the whole world with nothing in
     * the chunk cache and costs tens of milliseconds; the estimate climbs to match; and then reuse makes captures
     * cheap again while the rate sits at a fraction of a frame a second, reporting itself held by a budget it was
     * spending a tenth of. Coming down fast makes that a capture or two rather than ten seconds.
     */
    private static final double SMOOTHING_DOWN = 0.6;

    /** No more often than a tick, since nothing it reads can change faster and the division is the expensive part. */
    private static final long ALLOCATE_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1) / TICKS_PER_SECOND;

    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    private volatile double budgetNanosPerSecond;
    private volatile int fpsCeiling;
    private volatile double maxMillisPerTick;

    private long allocatedAt;

    private static final class Viewer {

        private long lastAsked;
        private long lastFrame;
        private double costNanos = ASSUMED_NANOS;
        private double fps;

        /** Set when this view was told yes and cleared by the capture that followed, so a still cannot claim it. */
        private boolean owed;
    }

    /**
     * What the live views are getting, for the report that has to explain a number somebody configured.
     *
     * @param usedMillisPerTick what the rates handed out add up to, which is the figure {@link #maxMillisPerTick}
     *                          only means anything against - well under it means the ceiling is what is binding
     */
    record Live(int viewers, double slowestFps, double fastestFps, double usedMillisPerTick) {

        /** Nobody looking through one, which is a state to report rather than an absence to null-check for. */
        static final Live NONE = new Live(0, 0, 0, 0);
    }

    CaptureBudget(double millisPerTick, int fpsCeiling) {
        this(millisPerTick, fpsCeiling, System::nanoTime);
    }

    CaptureBudget(double millisPerTick, int fpsCeiling, LongSupplier nanos) {
        this.clock = nanos;
        retune(millisPerTick, fpsCeiling);
    }

    /**
     * @param millisPerTick main-thread time a tick may spend on live views, or 0 for no budget at all
     * @param fpsCeiling    the most frames a second any one view may take, or 0 for no ceiling
     */
    void retune(double millisPerTick, int fpsCeiling) {
        this.maxMillisPerTick = millisPerTick;
        this.budgetNanosPerSecond = millisPerTick <= 0
                ? Double.MAX_VALUE
                : millisPerTick * 1_000_000 * TICKS_PER_SECOND;
        this.fpsCeiling = fpsCeiling;
    }

    /**
     * Whether a live view of this player should take a frame now.
     *
     * <p>Asking is what makes them a viewer, so this has to be called every time a view would like a frame rather than
     * only when it intends to take one - a screen that asks once a second is a screen that wanted one frame a second,
     * and it will be divided by as one.
     */
    boolean readyForFrame(UUID player) {
        long now = clock.getAsLong();
        Viewer viewer = viewers.get(player);

        if (viewer == null) {
            viewer = new Viewer();
            viewer.costNanos = typicalCost();
            viewers.put(player, viewer);
            // At once rather than at the next tick, or the first frame of a new view would be paced by a division
            // that has never heard of it.
            allocatedAt = 0;
        }
        viewer.lastAsked = now;
        allocate(now);

        if (viewer.fps <= 0) return false;

        long interval = (long) (TimeUnit.SECONDS.toNanos(1) / viewer.fps);
        if (now - viewer.lastFrame < interval) return false;

        viewer.lastFrame = now;
        viewer.owed = true;
        return true;
    }

    /**
     * Whether the next capture for this player is the one a yes was just given for.
     *
     * <p>What separates a paced frame from a still taken by the same person, which matters twice. A still is not
     * measured into the view's cost - a 256-pixel photograph copies a far wider frustum than a 64-pixel viewfinder,
     * so letting one into the average would slow that player's view for a second over a capture that was never part
     * of it. And a capture nobody asked permission for is outside the budget entirely, which the report says out
     * loud rather than quietly counting as if the budget had allowed it.
     */
    boolean claimPaced(UUID player) {
        Viewer viewer = viewers.get(player);
        if (viewer == null || !viewer.owed) return false;

        viewer.owed = false;
        return true;
    }

    /**
     * What a paced capture from this player's eye actually cost the tick, which is what the next division is made of.
     *
     * <p>Only the paced ones. What a capture costs is a function of how wide and how far it was asked to see, so a
     * still at a different size is a measurement of something else - see {@link #claimPaced}.
     */
    void spent(UUID player, long mainNanos) {
        Viewer viewer = viewers.get(player);
        if (viewer == null) return;

        double weight = mainNanos < viewer.costNanos ? SMOOTHING_DOWN : SMOOTHING_UP;
        viewer.costNanos = viewer.costNanos * (1 - weight) + mainNanos * weight;
    }

    /** The budget an admin set, for a report that has to say which of the two limits is the binding one. */
    double maxMillisPerTick() {
        return maxMillisPerTick;
    }

    int fpsCeiling() {
        return fpsCeiling;
    }

    /**
     * What this one player's view is being allowed, or 0 when they have not asked lately.
     *
     * <p>Divided again first, the way {@link #live} is. Otherwise this reports the last division rather than the
     * current one - and the moment somebody asks is usually the moment something has just changed, which is the
     * one time a stale answer is worst.
     */
    double frameRate(UUID player) {
        long now = clock.getAsLong();
        allocate(now);

        Viewer viewer = viewers.get(player);
        if (viewer == null || now - viewer.lastAsked >= IDLE_NANOS) return 0;

        return viewer.fps;
    }

    /** {@link Live#NONE} when nobody is looking through one, so a caller reads a count rather than a null. */
    Live live() {
        long now = clock.getAsLong();
        allocate(now);

        double slowest = Double.MAX_VALUE;
        double fastest = 0;
        double nanosPerSecond = 0;
        int counted = 0;

        for (Viewer viewer : viewers.values()) {
            if (now - viewer.lastAsked >= IDLE_NANOS) continue;

            counted++;
            slowest = Math.min(slowest, viewer.fps);
            fastest = Math.max(fastest, viewer.fps);
            // What the rate handed out will cost, rather than what it has cost: this is the allocation explaining
            // itself, and a viewer that has just been slowed down has not spent the new number yet.
            nanosPerSecond += viewer.fps * viewer.costNanos;
        }

        double perTick = nanosPerSecond / TICKS_PER_SECOND / 1_000_000;
        return counted == 0 ? Live.NONE : new Live(counted, slowest, fastest, perTick);
    }

    /**
     * Divides the budget over everybody still looking, giving each of them a rate.
     *
     * <p>Cheapest first, and each in turn offered an even split of what is left. A view that would hit the ceiling on
     * less than its split takes only what it needs and hands the rest back, so the ones that cannot reach the ceiling
     * get more than an even share - which is the whole of "as much as possible with the time given". The first view
     * that cannot afford the ceiling on its split is the point where nobody after it can either, since they cost more,
     * so the rest share what is left evenly and the loop is done.
     */
    private void allocate(long now) {
        if (now - allocatedAt < ALLOCATE_EVERY_NANOS && allocatedAt != 0) return;
        allocatedAt = now;

        List<Viewer> active = new ArrayList<>();
        for (Iterator<Viewer> it = viewers.values().iterator(); it.hasNext(); ) {
            Viewer viewer = it.next();
            if (now - viewer.lastAsked >= IDLE_NANOS) {
                it.remove();
                continue;
            }
            active.add(viewer);
        }
        if (active.isEmpty()) return;

        // No ceiling is a ceiling nothing reaches, which is the same arithmetic without a branch through the middle.
        double ceiling = fpsCeiling <= 0 ? Double.MAX_VALUE : fpsCeiling;
        active.sort(Comparator.comparingDouble(viewer -> viewer.costNanos));

        double remaining = budgetNanosPerSecond;
        for (int i = 0; i < active.size(); i++) {
            double share = remaining / (active.size() - i);
            Viewer viewer = active.get(i);
            double wanted = ceiling * viewer.costNanos;

            if (wanted <= share) {
                viewer.fps = ceiling;
                remaining -= wanted;
                continue;
            }

            for (int rest = i; rest < active.size(); rest++) {
                active.get(rest).fps = share / active.get(rest).costNanos;
            }
            return;
        }
    }

    /** What a new view is assumed to cost: whatever the others are costing, since they are looking at the same world. */
    private double typicalCost() {
        double total = 0;
        int counted = 0;

        for (Viewer viewer : viewers.values()) {
            total += viewer.costNanos;
            counted++;
        }
        return counted == 0 ? ASSUMED_NANOS : total / counted;
    }
}
