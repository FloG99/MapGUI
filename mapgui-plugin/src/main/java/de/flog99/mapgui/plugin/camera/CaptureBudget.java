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

    /** How much of a new measurement replaces the old one. A copy varies with direction and with what is cached. */
    private static final double SMOOTHING = 0.25;

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
    }

    /** What the live views are getting, for the report that has to explain a number somebody configured. */
    record Live(int viewers, double slowestFps, double fastestFps, double maxMillisPerTick, int fpsCeiling) {
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
        return true;
    }

    /**
     * What a capture from this player's eye actually cost the tick, which is what the next division is made of.
     *
     * <p>Fed by every capture rather than only by paced ones: the cost of copying the world around somebody is the
     * same whoever asked for it, and a still taken mid-view is a free measurement.
     */
    void spent(UUID player, long mainNanos) {
        Viewer viewer = viewers.get(player);
        if (viewer == null) return;

        viewer.costNanos = viewer.costNanos * (1 - SMOOTHING) + mainNanos * SMOOTHING;
    }

    /** Null when nobody is looking through one, since a report about no viewers is a line to learn to skip. */
    Live live() {
        long now = clock.getAsLong();
        allocate(now);

        double slowest = Double.MAX_VALUE;
        double fastest = 0;
        int counted = 0;

        for (Viewer viewer : viewers.values()) {
            if (now - viewer.lastAsked >= IDLE_NANOS) continue;

            counted++;
            slowest = Math.min(slowest, viewer.fps);
            fastest = Math.max(fastest, viewer.fps);
        }

        return counted == 0 ? null : new Live(counted, slowest, fastest, maxMillisPerTick, fpsCeiling);
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
