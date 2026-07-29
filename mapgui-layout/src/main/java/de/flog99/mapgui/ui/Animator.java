package de.flog99.mapgui.ui;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Eases values toward whatever they were last asked to be.
 *
 * <p>Nodes are rebuilt on every state change, so animation state lives here instead, keyed per node and
 * property like scroll offsets. Which is also why an animated node wants a {@link AbstractNode#key}: without
 * one it is identified by its position in the tree, and that moves when the tree changes shape.
 *
 * <p>Callers only say what the value should be now. If the target changed, a new run starts from wherever the
 * last had got to, so a target that keeps moving never jumps.
 */
public final class Animator {

    /**
     * Map updates go out once a server tick at best, so an animation gets a frame every 50ms. Much
     * shorter than this and there are too few frames for the ease to read as one.
     */
    public static final int DEFAULT_DURATION_MS = 250;

    /** A map update goes out once a server tick at best, so no frame limit above this means anything. */
    public static final int MAX_FPS = 20;

    /** Loops read fine at half the rate of a transition and cost half as much, so they default lower. */
    public static final int DEFAULT_LOOP_FPS = 10;

    /** Finished tracks unused for this many layout passes are dropped. */
    private static final int FORGET_AFTER_PASSES = 2;

    private final Map<String, Track<Double>> numbers = new HashMap<>();
    private final Map<String, Track<Color>> colors = new HashMap<>();

    private long now;
    private long pass;
    private long loopingPass = Long.MIN_VALUE;
    private boolean enabled = true;
    private int loopIntervalMs;

    private static final class Track<T> {
        T from;
        T to;
        long start;
        int duration;
        Easing easing;
        long pass;
    }

    /** Advances the clock. Called once per frame by whatever drives rendering. */
    public void clock(long millis) {
        now = millis;
    }

    /**
     * Starts a layout pass, and forgets anything that stopped being laid out a while ago.
     *
     * <p>Counted in passes rather than milliseconds because an idle screen is not laid out at all, so a timer
     * would throw away the value a scroll needs to animate <i>from</i>.
     */
    public void beginLayout() {
        pass++;
        forgetStale(numbers);
        forgetStale(colors);
    }

    public long now() {
        return now;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean value) {
        this.enabled = value;
        if (!value) {
            reset();
        }
    }

    public void reset() {
        numbers.clear();
        colors.clear();
        loopingPass = Long.MIN_VALUE;
    }

    /**
     * A 0..1 sawtooth that never settles, for animations that loop rather than arrive - a scrolling rainbow,
     * a spinner, a pulse. Asking for it is also what keeps frames coming: a screen that stops calling this
     * stops being repainted a couple of passes later.
     *
     * <p><b>It costs bandwidth for as long as it is on screen, and it never stops.</b> A full-canvas effect
     * is 16 KB a frame - about 320 KB/s, or 2.6 Mbit/s, for one player - while a 100x8 label is nearer
     * 16 KB/s. The cost tracks the area that changes, so keep looping effects small.
     */
    public double phase(int periodMillis) {
        if (!enabled || periodMillis <= 0) return 0;

        loopingPass = pass;
        // Quantizing the clock rather than the result is what makes the limit cost nothing: the value
        // stops changing between steps, so the pixels do too, so there is no dirty rect to send.
        long clock = loopIntervalMs <= 0 ? now : now - Math.floorMod(now, loopIntervalMs);
        return Math.floorMod(clock, periodMillis) / (double) periodMillis;
    }

    /**
     * Caps how often looping effects step, leaving transitions alone. A period shorter than a few
     * steps will read as stuttering rather than looping, which is the only thing to watch for.
     */
    public void loopFps(int fps) {
        loopIntervalMs = fps <= 0 ? 0 : 1000 / Math.min(fps, MAX_FPS);
    }

    public int loopIntervalMs() {
        return loopIntervalMs;
    }

    /** True while any value is still moving, which is the cue to keep drawing frames. */
    public boolean animating() {
        return looping() || transitioning();
    }

    /** True while a looping effect is on screen. Unlike a transition these never finish by themselves. */
    public boolean looping() {
        if (!enabled) return false;
        // Not "pass - loopingPass <= 1": that overflows against the initial sentinel.
        return loopingPass >= pass - 1;
    }

    /** True while something is easing toward a target, so it will stop of its own accord. */
    public boolean transitioning() {
        if (!enabled) return false;

        return numbers.values().stream().anyMatch(this::running)
                || colors.values().stream().anyMatch(this::running);
    }

    public double value(String key, double target, int duration, Easing easing) {
        if (!enabled || duration <= 0) return target;

        Track<Double> track = numbers.get(key);
        if (track == null) {
            numbers.put(key, start(target, target, duration, easing));
            return target;
        }

        track.pass = pass;
        if (track.to != target) {
            retarget(track, interpolate(track, this::lerp), target, duration, easing);
        }
        return interpolate(track, this::lerp);
    }

    public Color color(String key, Color target, int duration, Easing easing) {
        if (!enabled || duration <= 0 || target == null) return target;

        Track<Color> track = colors.get(key);
        if (track == null) {
            colors.put(key, start(target, target, duration, easing));
            return target;
        }

        track.pass = pass;
        if (!target.equals(track.to)) {
            retarget(track, interpolate(track, Colors::mix), target, duration, easing);
        }
        return interpolate(track, Colors::mix);
    }

    // ---- internals ----

    /**
     * A value seen for the first time is already where it belongs, so the track is backdated to
     * finished - otherwise the very first read would report itself as animating.
     */
    private <T> Track<T> start(T from, T to, int duration, Easing easing) {
        Track<T> track = new Track<>();
        track.from = from;
        track.to = to;
        track.start = now - duration;
        track.duration = duration;
        track.easing = easing;
        track.pass = pass;
        return track;
    }

    private <T> void retarget(Track<T> track, T current, T target, int duration, Easing easing) {
        track.from = current;
        track.to = target;
        track.start = now;
        track.duration = duration;
        track.easing = easing;
    }

    private <T> T interpolate(Track<T> track, Lerp<T> lerp) {
        double elapsed = now - track.start;
        double progress = track.duration <= 0 ? 1 : Math.min(1, Math.max(0, elapsed / track.duration));
        return lerp.at(track.from, track.to, track.easing.apply(progress));
    }

    private double lerp(double from, double to, double amount) {
        return from + (to - from) * amount;
    }

    private boolean running(Track<?> track) {
        return now < track.start + track.duration;
    }

    private void forgetStale(Map<String, ? extends Track<?>> tracks) {
        Iterator<? extends Map.Entry<String, ? extends Track<?>>> entries = tracks.entrySet().iterator();
        while (entries.hasNext()) {
            Track<?> track = entries.next().getValue();
            if (!running(track) && pass - track.pass > FORGET_AFTER_PASSES) {
                entries.remove();
            }
        }
    }

    private interface Lerp<T> {
        T at(T from, T to, double amount);
    }
}
