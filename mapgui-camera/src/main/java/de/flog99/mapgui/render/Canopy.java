package de.flog99.mapgui.render;

/**
 * How far out a leaf's gaps close up, so a forest reads as a mass rather than a haze of twigs with daylight behind it.
 *
 * <p>A resolution argument rather than a taste one. A leaf texture is 16x16 across a block face, so past some distance
 * a texel is smaller than a pixel of the capture and a gap no longer has a pixel of its own to be seen through - what
 * the pixel covers is part leaf and part gap. From {@link #near} the gaps fill in over the texture's own average
 * color, reaching solid at {@link #far}. The client arrives at the same place by mipmapping.
 *
 * <p>Where that crossover really falls depends on the size and field of view a capture is taken at, which is why this
 * is a setting rather than the two constants it used to be.
 *
 * @param near where the gaps start closing, in blocks, or 0 to close them from the lens out
 * @param far  where a canopy is solid. Never nearer than {@link #near}
 */
public record Canopy(double near, double far) {

    /** Solid at fifty blocks, closing from the lens. Filling from zero because a far tree is what this is for. */
    public static final Canopy DEFAULT = new Canopy(0, 50);

    /** Leaves left as the cutout they are on disk, at any distance. */
    public static final Canopy OFF = new Canopy(Double.MAX_VALUE, Double.MAX_VALUE);

    public Canopy {
        near = Math.max(0, near);
        far = Math.max(near, far);
    }

    /**
     * How much of a gap this distance fills, 0 to 1.
     *
     * <p>The equal ends are what makes a canopy configured with one distance a hard switch rather than a division by
     * zero.
     */
    public float fill(double distance) {
        if (distance <= near) return 0;
        if (distance >= far) return 1;

        return (float) ((distance - near) / (far - near));
    }
}
