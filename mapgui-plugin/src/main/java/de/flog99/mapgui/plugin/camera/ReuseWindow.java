package de.flog99.mapgui.plugin.camera;

import java.util.concurrent.TimeUnit;

/**
 * How long something this far from the camera may be reused for. The one rule behind all three caches.
 *
 * <p>Staleness is only worth what it hides: what is at your feet is most of the picture, what is at the horizon is a
 * few pixels. Ramped rather than stepped, or things visibly catch up as you walk toward them.
 *
 * <p>Distances are in whatever the caller measures in - chunks for the copy, blocks for the rest.
 *
 * @param nearNanos how long something inside {@code near} may be reused for, {@code farNanos} the same at
 *                  {@code far}. A far window shorter than the near one is a typo, so it is raised rather than obeyed
 */
public record ReuseWindow(long nearNanos, long farNanos, double near, double far) {

    public ReuseWindow {
        nearNanos = Math.max(0, nearNanos);
        farNanos = Math.max(nearNanos, farNanos);
        near = Math.max(0, near);
        far = Math.max(near, far);
    }

    /** The same in the unit config.yml writes. */
    public static ReuseWindow ofMillis(long nearMillis, long farMillis, double near, double far) {
        return new ReuseWindow(TimeUnit.MILLISECONDS.toNanos(Math.max(0, nearMillis)),
                TimeUnit.MILLISECONDS.toNanos(Math.max(0, farMillis)), near, far);
    }

    /** Nothing is ever reused. */
    public static final ReuseWindow NONE = new ReuseWindow(0, 0, 0, 0);

    /** @param away from the camera, in this window's own unit */
    public long allowedAgeNanos(double away) {
        if (away <= near) return nearNanos;
        if (away >= far) return farNanos;

        return nearNanos + (long) ((farNanos - nearNanos) * ((away - near) / (far - near)));
    }

    /** The longest anything may be served for, which is what an expiry sweep has to keep. */
    public long longestNanos() {
        return farNanos;
    }

    /** Whether this holds anything, since a window of nothing is a cache that only costs memory. */
    public boolean enabled() {
        return farNanos > 0;
    }

    public int nearMillis() {
        return (int) TimeUnit.NANOSECONDS.toMillis(nearNanos);
    }

    public int farMillis() {
        return (int) TimeUnit.NANOSECONDS.toMillis(farNanos);
    }
}
