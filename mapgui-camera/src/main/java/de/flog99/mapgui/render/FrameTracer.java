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

    private final Textures atlas;
    private final int threads;
    private final ExecutorService pool;

    /** One per thread, created once. Building a caster allocates its scratch, so this is not per frame work. */
    private final ThreadLocal<RayCaster> casters;

    public FrameTracer(Textures atlas) {
        this(atlas, Canopy.DEFAULT);
    }

    public FrameTracer(Textures atlas, Canopy canopy) {
        this(atlas, canopy, threadsFor(Runtime.getRuntime().availableProcessors()));
    }

    FrameTracer(Textures atlas, int threads) {
        this(atlas, Canopy.DEFAULT, threads);
    }

    FrameTracer(Textures atlas, Canopy canopy, int threads) {
        this.atlas = atlas;
        this.threads = threads;
        this.casters = ThreadLocal.withInitial(() -> new RayCaster(atlas, canopy));
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
        int bands = Math.min(threads, height);
        if (pool == null || bands <= 1) {
            casters.get().render(world, view, entities, width, height, out);
            return;
        }

        List<Future<?>> pending = new ArrayList<>(bands);
        for (int band = 0; band < bands; band++) {
            int fromRow = height * band / bands;
            int toRow = height * (band + 1) / bands;
            pending.add(pool.submit(() -> casters.get().render(world, view, entities, width, height, out, fromRow, toRow)));
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
