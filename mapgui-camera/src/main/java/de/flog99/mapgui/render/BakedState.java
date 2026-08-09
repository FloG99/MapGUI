package de.flog99.mapgui.render;

import java.util.List;

/**
 * One block state, resolved down to what a ray needs to hit it.
 *
 * <p>Always an element list, never a special case for cubes, so stairs, slabs and fences come out of the same code
 * as stone. {@link #fullCube} exists only so the renderer can skip the slab test where the grid walk has already
 * reported the face, which is the common case and worth a branch. Coordinates stay in model space, since that is
 * what the json says.
 *
 * @param water  whether this block holds water, so the face between two of them is dropped and a pool reads as one
 *               surface. Comparing states is not enough: a source, a flowing block and a waterlogged stair are three
 *               states with the same water in them
 * @param leaves whether the gaps in this block's texture close up with distance - only leaves, because only leaves
 *               are a thing you look at a hundred of at once
 * @param fluidTop how high the fluid in this block stands, in sixteenths, and 0 for a block holding none. Kept here
 *               rather than worked out from the elements because the culling reads it once per face per ray: a
 *               fluid's side only vanishes against a neighbour whose own fluid is at least as high, or the taller of
 *               two adjacent pools is a hole you can see the riverbed through
 * @param fluidFlow the texture a moving fluid's surface is drawn with, or null for a block holding none. A second
 *               texture rather than the still one turned, because the two are different images: the still one has
 *               no direction in it at all, so a stream drawn with it runs nowhere however it is rotated
 */
public record BakedState(List<BakedElement> elements, boolean fullCube, Alpha alpha, boolean water, boolean leaves,
                         float fluidTop, String fluidFlow) {

    /**
     * How a ray should treat this block. Read off the textures rather than a hardcoded material list: the
     * png's own alpha channel says whether it is a cutout, and the model json says when a texture that looks
     * opaque should blend anyway.
     */
    public enum Alpha {

        /** Stops the ray. */
        OPAQUE,

        /** Blends and the ray carries on - glass, ice, water. */
        TRANSLUCENT,

        /** Per-texel all-or-nothing - leaves, bars, grass. The ray carries on where the texel is empty. */
        CUTOUT
    }

    /** Nothing to draw and nothing to stop a ray: air, and anything whose model resolved to no geometry. */
    public static final BakedState EMPTY = new BakedState(List.of(), false, Alpha.TRANSLUCENT, false);

    /** Dry, for a caller that has geometry and no fluid in it. */
    public BakedState(List<BakedElement> elements, boolean fullCube, Alpha alpha) {
        this(elements, fullCube, alpha, false, false, 0, null);
    }

    public BakedState(List<BakedElement> elements, boolean fullCube, Alpha alpha, boolean water) {
        this(elements, fullCube, alpha, water, false, 0, null);
    }

    /** Dry, for a caller that has already worked out the rest. */
    public BakedState(List<BakedElement> elements, boolean fullCube, Alpha alpha, boolean water, boolean leaves,
                      float fluidTop) {
        this(elements, fullCube, alpha, water, leaves, fluidTop, null);
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }
}
