package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A frame traced across several threads, as bands of rows.
 *
 * <p>The rays of a frame do not interact - each walks the world, reads immutable textures and writes one pixel - and
 * {@link RayCaster} keeps its scratch per instance, so one caster per thread and a band each leaves nothing to
 * synchronize. Bands rather than tiles, because a row is contiguous in the output array and two threads writing
 * neighbouring rows never touch the same cache line.
 *
 * <p>Deliberately not the common pool: a capture is background work on somebody's game server, and a small pool of
 * named daemon threads is easier to account for in a profile and cannot outlive the plugin.
 */
public final class FrameTracer implements AutoCloseable {

    /**
     * How many threads to trace with.
     *
     * <p>Two below the processor count and never more than six. The point is to finish a capture quickly, not to own
     * the machine: the server has a main thread doing the actual game and its own worker pools for chunk loading and
     * network, and taking every core for a photograph would be a poor trade even though the trace itself is off the
     * main thread.
     */
    static int threadsFor(int processors) {
        return Math.clamp(processors - 2, 1, 6);
    }

    /**
     * How many bands to cut the frame into per thread, rather than one each.
     *
     * <p>Because {@link #render} waits for all of them, so a frame costs the <b>slowest</b> band and not the average one -
     * and the rows of a frame are nothing like equally expensive. A band of sky is a handful of steps a ray; a band across
     * the horizon walks the full distance cap through terrain, foliage and water.
     *
     * <p>Measured on a live server tracing a mirror, six threads, one band each: cumulative CPU per thread came out
     * <b>85.3 s, 20.3, 16.0, 26.8, 16.1, 73.5</b>. The busiest band held 36% of the work where an even split is 17%, so
     * the frame took <b>2.1 times</b> as long as the same work would have taken shared out - and every capture pays this,
     * a photograph as much as a reflection.
     *
     * <p>Four apiece is enough to average that away: the pool hands the next band to whichever thread finished first, so
     * the worst a thread can end up with is its share plus one band, and a band is now a fifth of what it was. More than
     * four buys little and each band re-derives the frame's own setup - which is why {@link EntityScreen}, the one piece
     * of that setup that is not trivial, is now built once per frame and shared.
     */
    private static final int BANDS_PER_THREAD = 4;

    private final Textures atlas;
    private final int threads;
    private final ExecutorService pool;

    /** One per thread, created once. Building a caster allocates its scratch, so this is not per frame work. */
    private final ThreadLocal<RayCaster> casters;

    public FrameTracer(Textures atlas) {
        this(atlas, Canopy.DEFAULT);
    }

    public FrameTracer(Textures atlas, Canopy canopy) {
        this(atlas, canopy, RayCaster.SHADOW_LIFT);
    }

    /** @param shadowLift how far off black an unlit block is drawn - see {@link RayCaster#SHADOW_LIFT} */
    public FrameTracer(Textures atlas, Canopy canopy, float shadowLift) {
        this(atlas, canopy, shadowLift, threadsFor(Runtime.getRuntime().availableProcessors()));
    }

    FrameTracer(Textures atlas, int threads) {
        this(atlas, Canopy.DEFAULT, RayCaster.SHADOW_LIFT, threads);
    }

    FrameTracer(Textures atlas, Canopy canopy, float shadowLift, int threads) {
        this.atlas = atlas;
        this.threads = threads;
        this.casters = ThreadLocal.withInitial(() -> new RayCaster(atlas, canopy, shadowLift));
        this.pool = threads > 1 ? Executors.newFixedThreadPool(threads, named()) : null;
    }

    private static ThreadFactory named() {
        AtomicInteger next = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "MapGUI-camera-" + next.incrementAndGet());
            // Daemon, so a server shutdown is never held up by a capture nobody is waiting for any more.
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
    }

    public void render(VoxelSource world, CameraView view, int width, int height, int[] out) {
        render(world, view, List.of(), width, height, out);
    }

    /**
     * Renders the whole frame, returning once every band is done.
     *
     * <p>Blocking, because the caller is already on a thread of its own waiting for exactly this - the capture is
     * async as a whole, and handing back a half-drawn frame would only move the waiting somewhere less obvious.
     */
    public void render(VoxelSource world, CameraView view, List<EntitySnapshot> entities, int width, int height, int[] out) {
        if (pool == null || threads <= 1 || height <= 1) {
            casters.get().render(world, view, entities, width, height, out);
            return;
        }

        int bands = Math.min(height, threads * BANDS_PER_THREAD);
        // Once per frame rather than once per band, now that there are several bands to a thread. It is immutable after
        // it is built and published through the pool's own queue, so every band reads the one copy safely.
        EntityScreen screen = entities.isEmpty() ? null : new EntityScreen(entities, view, width, height);

        List<Future<?>> pending = new ArrayList<>(bands);
        for (int band = 0; band < bands; band++) {
            int fromRow = height * band / bands;
            int toRow = height * (band + 1) / bands;
            pending.add(pool.submit(() ->
                    casters.get().render(world, view, entities, screen, width, height, out, fromRow, toRow)));
        }

        for (Future<?> band : pending) {
            try {
                band.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while tracing a frame", e);
            } catch (java.util.concurrent.ExecutionException e) {
                // Unwrapped, so a caller sees the failure the band actually hit rather than a wrapper around it.
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            }
        }
    }

    /** For a log line that says what the camera is using. */
    public int threads() {
        return threads;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
