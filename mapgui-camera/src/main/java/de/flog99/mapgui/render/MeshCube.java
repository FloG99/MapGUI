package de.flog99.mapgui.render;

/**
 * One cuboid of an entity model, in its part's own space, with the texture coordinates of each corner it draws.
 *
 * <p>Corner UVs rather than a {@code u, v, width, height} patch and a set of orientation flags. Vanilla builds a
 * cube's six quads with whatever winding and mirroring the model asked for, and the four coordinates say what that
 * came out as without anything having to classify it - a mirrored limb, a body laid on its side and a face read
 * upside down are all just four pairs of numbers, and bilinear interpolation over them is exact because the quad is
 * a rectangle in both spaces.
 *
 * @param faces per {@link Direction#ordinal()}, eight floats of {@code u, v} at the four corners in
 *              {@link #corner} order and normalized to 0..1, or null for a side the model does not draw
 */
record MeshCube(
        float minX, float minY, float minZ,
        float maxX, float maxY, float maxZ,
        float[][] faces) {

    /**
     * Which of a face's four UV pairs belongs to a corner, in the same {@code across, down} the tracer measures a
     * hit in. Two bits, so the slot is an index rather than a search.
     */
    static int corner(boolean across, boolean down) {
        return (down ? 2 : 0) + (across ? 1 : 0);
    }

    /**
     * A box unwrapped the way Minecraft lays a skin out: the four sides in a row with the top and bottom above
     * them, reading from {@code u, v} as the top left of the whole patch.
     *
     * <p>For the models this authors rather than extracts - the player, a bounding box, a dropped item. Sizes are the
     * box's own, so a 3-pixel slim arm indexes a narrower patch with no second table.
     *
     * <p><b>Measured against an extracted vanilla cube rather than reasoned out</b>, since reasoning it out is how it
     * was wrong: the strip runs the mob's <i>right</i> side first, which is {@link Direction#EAST} on a model facing
     * north, and it runs against the direction {@link #across} measures - hence {@link #patch} handing the high u to
     * across zero.
     *
     * @param grow how far out on every side without changing the patch it reads. What a skin's overlay layer needs:
     *             coincident with the base layer it would be a coin toss which one a ray reached first
     */
    static MeshCube box(float x, float y, float z, float width, float height, float depth,
                        int u, int v, int textureWidth, int textureHeight, float grow) {

        float[][] faces = new float[6][];
        faces[Direction.EAST.ordinal()] = patch(u, v + depth, depth, height, textureWidth, textureHeight, false);
        faces[Direction.NORTH.ordinal()] = patch(u + depth, v + depth, width, height, textureWidth, textureHeight, false);
        faces[Direction.WEST.ordinal()] = patch(u + depth + width, v + depth, depth, height, textureWidth, textureHeight, false);
        faces[Direction.SOUTH.ordinal()] = patch(u + depth + width + depth, v + depth, width, height, textureWidth, textureHeight, false);
        // The top folds up from the front, so its lower edge is the one against the face - which is why this side
        // alone reads v the other way up.
        faces[Direction.UP.ordinal()] = patch(u + depth, v, width, depth, textureWidth, textureHeight, true);
        faces[Direction.DOWN.ordinal()] = patch(u + depth + width, v, width, depth, textureWidth, textureHeight, false);

        return new MeshCube(x - grow, y - grow, z - grow, x + width + grow, y + height + grow, z + depth + grow, faces);
    }

    /**
     * A flat quad with the whole texture on its front and its back, and its own outermost pixels around the rim.
     *
     * <p>What an item is, and what the client draws: a generated item model is the icon extruded one pixel, front and
     * back with side faces around it. So the rim is the other four faces of that extrusion rather than decoration.
     *
     * <p>Each rim face samples the row or column of the icon it lies against, stretched across the thickness. Where
     * the icon does not reach that edge the sample is transparent and nothing draws, which is why an icon that sits
     * inside its frame wants {@link SpriteShape} instead.
     */
    static MeshCube sprite(float x, float y, float z, float width, float height, float thickness) {
        return sprite(x, y, z, width, height, thickness, 0, 0, 1, 1, SIXTEENTH / 2, SIXTEENTH / 2);
    }

    /** One texel of the sixteen an item icon is authored in, for a caller that does not know the real resolution. */
    private static final float SIXTEENTH = 1 / 16f;

    /**
     * One box of a sprite, carrying a stated rectangle of the icon rather than the whole of it.
     *
     * <p>Which is what an extrusion that follows the picture needs - see {@link SpriteShape}. The rim of the whole
     * icon lies on the frame it is drawn in, and most icons keep clear of their frame, so a rim built there is a rim
     * around nothing: a sword's blade only reaches its frame at the tip, which is exactly where the extrusion showed.
     * Built as several boxes over the picture's own bands instead, each rim face lands against real pixels.
     *
     * <p>A rim face is a single line of the texture, so which line it lands on is decided by a coordinate rather than
     * averaged over one - and a texture is sampled by flooring, so the far edge of a rectangle names the texel
     * <i>past</i> it. Left there, the right and bottom rim of every box read whatever is next door: nothing at all on
     * anything a texel wide, which is a bow's string and most of what is thin in an icon. So the rim is taken half a
     * texel inside the rectangle, where it can only land on the box's own picture.
     *
     * @param u1    the rectangle in texture coordinates, normalized, {@code u1} and {@code v1} the smaller ends
     * @param halfU half of one texel of this icon, normalized, and {@code halfV} the same down it
     */
    static MeshCube sprite(float x, float y, float z, float width, float height, float thickness,
                           float u1, float v1, float u2, float v2, float halfU, float halfV) {

        float[][] faces = new float[6][];
        // The rim: east lies against the low end of u because the front reads its u against x, up against the top of
        // v, and each opposite face against the other end of its own rectangle.
        faces[Direction.EAST.ordinal()] = column(u1 + halfU, v1 + halfV, v2 - halfV);
        faces[Direction.WEST.ordinal()] = column(u2 - halfU, v1 + halfV, v2 - halfV);
        faces[Direction.UP.ordinal()] = row(v1 + halfV, u1 + halfU, u2 - halfU);
        faces[Direction.DOWN.ordinal()] = row(v2 - halfV, u1 + halfU, u2 - halfU);
        // The picture faces are inset by the same half texel, and for the same reason: their u runs backwards, so the
        // corner the sampler can reach exactly is the rectangle's far end and would name the texel past it.
        float insetU = u1 + halfU;
        float insetV = v1 + halfV;
        float acrossInset = Math.max(0, u2 - u1 - 2 * halfU);
        float downInset = Math.max(0, v2 - v1 - 2 * halfV);

        faces[Direction.NORTH.ordinal()] = patch(insetU, insetV, acrossInset, downInset, 1, 1, false, false);
        // The back reads its u the other way, which is vanilla's own {@code NORTH_FACE_UVS} of (16, 0, 0, 16) against
        // the front's (0, 0, 16, 16). It is what a flat thing does: from behind you see the picture mirrored, and both
        // sides show the same texture column at the same point on the quad. Drawn un-reversed on both faces a sprite
        // reads correctly from one side and mirrored from the other - which nobody notices on a dropped apple and
        // everybody notices on a held bow.
        faces[Direction.SOUTH.ordinal()] = patch(insetU, insetV, acrossInset, downInset, 1, 1, false, true);

        return new MeshCube(x, y, z, x + width, y + height, z + thickness, faces);
    }

    /** One column of the texture across a whole face: u fixed, v running down it as {@link #down} measures. */
    private static float[] column(float u, float v1, float v2) {
        float[] corners = new float[8];
        put(corners, corner(false, false), u, v1);
        put(corners, corner(true, false), u, v1);
        put(corners, corner(false, true), u, v2);
        put(corners, corner(true, true), u, v2);
        return corners;
    }

    /** And one row: v fixed, u running across it the way {@link #patch} runs it, high u at across zero. */
    private static float[] row(float v, float u1, float u2) {
        float[] corners = new float[8];
        put(corners, corner(false, false), u2, v);
        put(corners, corner(true, false), u1, v);
        put(corners, corner(false, true), u2, v);
        put(corners, corner(true, true), u1, v);
        return corners;
    }

    /** Every side the whole texture, for a model with no unwrap to index. */
    static MeshCube plain(float x, float y, float z, float width, float height, float depth) {
        float[][] faces = new float[6][];
        for (Direction side : Direction.values()) {
            faces[side.ordinal()] = whole();
        }
        return new MeshCube(x, y, z, x + width, y + height, z + depth, faces);
    }

    /** One side reading the whole texture, for a cube built a face at a time - a painting's picture and its back. */
    static float[] whole() {
        return patch(0, 0, 1, 1, 1, 1, false);
    }


    float[] face(Direction direction) {
        return faces[direction.ordinal()];
    }

    /** The same cube somewhere else, reading the same texture. */
    MeshCube moved(float dx, float dy, float dz) {
        return new MeshCube(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz, faces);
    }

    /**
     * How far across one of this cube's sides a point on it sits, from 0 to 1.
     *
     * <p>Here rather than in the tracer because the extractor needs the same answer: it decides which corner each
     * of vanilla's four vertices is by measuring it, so if these two ever disagreed every UV would be filed under
     * the wrong corner and every face would come out rotated.
     */
    double across(Direction face, double x, double y, double z) {
        return switch (face) {
            case UP, DOWN, NORTH -> span(x, minX, maxX);
            case SOUTH -> 1 - span(x, minX, maxX);
            case WEST -> 1 - span(z, minZ, maxZ);
            case EAST -> span(z, minZ, maxZ);
        };
    }

    /** And how far down it, likewise from 0 to 1. The top and bottom read along Z instead of Y. */
    double down(Direction face, double x, double y, double z) {
        return switch (face) {
            case UP -> span(z, minZ, maxZ);
            case DOWN -> 1 - span(z, minZ, maxZ);
            default -> 1 - span(y, minY, maxY);
        };
    }

    /** Where between two bounds a coordinate sits, and the middle of a side with no extent on that axis. */
    private static double span(double at, double low, double high) {
        return high - low < 1e-6 ? 0.5 : (at - low) / (high - low);
    }

    /**
     * The point on one of this cube's sides that sits at a stated {@code across} and {@code down} - the inverse of
     * the two above, and here beside them so that neither can drift from the other.
     *
     * <p>What a baked block model needs. Its faces state where a texture is sampled in the block's own coordinates,
     * and a cube's faces state it per corner, so converting one to the other means asking where each corner is.
     *
     * <p>The coordinate across the face is the middle of it, since a side face has no extent that way and nothing
     * reads it.
     */
    float[] pointAt(Direction face, float across, float down) {
        float x = mix(minX, maxX, 0.5f);
        float y = mix(minY, maxY, 0.5f);
        float z = mix(minZ, maxZ, 0.5f);

        switch (face) {
            case UP, DOWN -> {
                x = mix(minX, maxX, across);
                z = mix(minZ, maxZ, face == Direction.UP ? down : 1 - down);
            }
            case NORTH, SOUTH -> {
                x = mix(minX, maxX, face == Direction.NORTH ? across : 1 - across);
                y = mix(minY, maxY, 1 - down);
            }
            case EAST, WEST -> {
                z = mix(minZ, maxZ, face == Direction.EAST ? across : 1 - across);
                y = mix(minY, maxY, 1 - down);
            }
        }

        return new float[]{x, y, z};
    }

    private static float mix(float low, float high, float by) {
        return low + (high - low) * by;
    }

    /**
     * An upright rectangle of texture as the four corners of it, normalized.
     *
     * <p>Across zero gets the <b>high</b> u, which is not a quirk. {@link #across} measures along the surface in the
     * direction of increasing x or z, and on a face seen from outside that direction runs leftward across the
     * viewer's screen - stand facing south and east is on your left. A texture reads rightward, so the two run
     * opposite ways, and the patch is handed over reversed rather than every reader remembering to reverse it.
     *
     * @param flipV for the one side whose texture is upside down against {@link #down} - see {@link #box}
     */
    private static float[] patch(float u, float v, float width, float height, int textureWidth, int textureHeight, boolean flipV) {
        return patch(u, v, width, height, textureWidth, textureHeight, flipV, false);
    }

    /**
     * @param flipU for a face that reads the patch the other way along, which is the back of a sprite - see
     *              {@link #sprite}
     */
    private static float[] patch(float u, float v, float width, float height, int textureWidth, int textureHeight, boolean flipV, boolean flipU) {
        float low = u / textureWidth;
        float high = (u + width) / textureWidth;
        float atZero = flipU ? low : high;
        float atOne = flipU ? high : low;
        float near = (flipV ? v + height : v) / textureHeight;
        float far = (flipV ? v : v + height) / textureHeight;

        float[] corners = new float[8];
        put(corners, corner(false, false), atZero, near);
        put(corners, corner(true, false), atOne, near);
        put(corners, corner(false, true), atZero, far);
        put(corners, corner(true, true), atOne, far);
        return corners;
    }

    private static void put(float[] corners, int slot, float u, float v) {
        corners[slot * 2] = u;
        corners[slot * 2 + 1] = v;
    }
}
