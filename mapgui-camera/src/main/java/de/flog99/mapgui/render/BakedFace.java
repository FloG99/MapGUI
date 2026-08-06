package de.flog99.mapgui.render;

/**
 * One drawn side of one box.
 *
 * @param texture  the resolved texture name, {@code minecraft:block/...} with the namespace already dropped
 * @param u1       texture coordinates in sixteenths, as the json states them - {@code u1 > u2} is legal and
 *                 means the face is mirrored, which several vanilla models rely on
 * @param rotation 0, 90, 180 or 270, turning the texture on the face without moving the face
 * @param tint     which of the world's tint colors multiplies this face, or {@link #NO_TINT}
 * @param cull     the neighbour that hides this face when it is solid, or null for a face always drawn. Water
 *                 and glass lean on it hardest: without it every block in a pool draws all six sides and a ray
 *                 through three of them stacks six translucent layers instead of looking like water
 * @param fluid    whether this is a fluid's own surface, which also disappears against a neighbour holding the
 *                 same fluid. Marked on the face rather than the block, so that a waterlogged stair's water
 *                 joins the pool it stands in while its stone sides still show
 */
public record BakedFace(String texture, float u1, float v1, float u2, float v2, int rotation, int tint,
                        Direction cull, boolean fluid) {

    /**
     * Most faces are drawn as they are. Only 33 of 2657 vanilla models tint anything - but those 33 include
     * grass, leaves and water, whose textures are greyscale on disk and come out looking like ash without it.
     */
    public static final int NO_TINT = Tints.NONE;

    /** A face read out of a model, which is never a fluid - the client builds those itself. */
    public BakedFace(String texture, float u1, float v1, float u2, float v2, int rotation, int tint, Direction cull) {
        this(texture, u1, v1, u2, v2, rotation, tint, cull, false);
    }

    /** The whole texture, unrotated and untinted, which is what a face with no {@code uv} means. */
    public static BakedFace whole(String texture) {
        return new BakedFace(texture, 0, 0, 16, 16, 0, NO_TINT, null);
    }

    /**
     * Where on a given side of a block a point sits, in the sixteenths a {@code uv} rect is stated in.
     *
     * <p>{@code v} runs downward from the top of a side face and {@code u} runs left to right as seen from outside
     * the block looking in - so facing north your right is west, and the north face's {@code u} runs the opposite
     * way to the south face's. That is why these are not all one expression.
     *
     * <p>Two scalars and no allocation, because the renderer asks this once per ray per face. Here rather than in
     * the renderer because a second reader needs the same answer: a block model hung off a hand is baked into
     * entity cubes, whose UVs have to be worked out with the convention the world is sampled with or the held block
     * wears its textures turned.
     */
    static double u(Direction face, double x, double y, double z) {
        return switch (face) {
            case UP, DOWN, SOUTH -> x;
            case NORTH -> 16 - x;
            case WEST -> z;
            case EAST -> 16 - z;
        };
    }

    static double v(Direction face, double x, double y, double z) {
        return switch (face) {
            case UP -> z;
            case DOWN -> 16 - z;
            default -> 16 - y;
        };
    }
}
