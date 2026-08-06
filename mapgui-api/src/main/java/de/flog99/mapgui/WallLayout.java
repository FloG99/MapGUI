package de.flog99.mapgui;

import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

/**
 * Where the maps of a wall go, in blocks.
 *
 * <p>Anchored at its bottom left as the viewer sees it, growing right and up - so the block you first
 * clicked stays put while the wall is being sized.
 *
 * <p>Integer arithmetic, kept apart from anything needing a server and tested directly: a reversed axis
 * puts a wall on the wrong side of a room or renders it mirrored, and neither is visible in the code.
 */
public record WallLayout(BlockFace facing, int anchorX, int anchorY, int anchorZ, int cols, int rows,
                          BlockFace right, BlockFace up) {

    /** Six is 36 maps, which is already 47 Mbit/s a viewer for full-frame video. Far enough. */
    public static final int MAX_SIDE = 6;

    public static final int TILE = MapSurface.TILE;

    /**
     * A one-by-one wall on the face that was clicked. Its up follows from that face - see {@link #upFor}.
     *
     * @throws IllegalArgumentException on anything but the six faces of a block. A diagonal has no axis to
     *         measure depth along, so it would render and aim nonsensically rather than fail
     */
    public static WallLayout anchoredAt(int x, int y, int z, BlockFace facing) {
        if (!facing.isCartesian()) {
            throw new IllegalArgumentException("A wall hangs on one of the six block faces, not " + facing);
        }

        BlockFace up = upFor(facing);
        return new WallLayout(facing, x, y, z, 1, 1, rightFor(facing, up), up);
    }

    /**
     * Which way is up in the picture, given the face it hangs on. Not a choice.
     *
     * <p>A wall takes the world's up. A floor or ceiling has none, and cannot pick one either: the client
     * recomputes a frame's yaw from the facing it is sent, so every horizontal frame is drawn at the same
     * angle whatever we ask for. That angle is a south-facing frame tipped up, which puts the picture's top
     * toward north on a floor and south on a ceiling. Matching it is what keeps a click landing where the
     * thing you clicked is drawn.
     */
    private static BlockFace upFor(BlockFace facing) {
        return switch (facing) {
            case UP -> BlockFace.NORTH;
            case DOWN -> BlockFace.SOUTH;
            default -> BlockFace.UP;
        };
    }

    /**
     * The viewer's right, which is their forward crossed with up - and their forward is the opposite of
     * the face, since a map faces the person looking at it.
     */
    private static BlockFace rightFor(BlockFace facing, BlockFace up) {
        int fx = -facing.getModX(), fy = -facing.getModY(), fz = -facing.getModZ();
        int ux = up.getModX(), uy = up.getModY(), uz = up.getModZ();
        return of(fy * uz - fz * uy, fz * ux - fx * uz, fx * uy - fy * ux);
    }

    private static BlockFace of(int x, int y, int z) {
        for (BlockFace face : BlockFace.values()) {
            if (face.getModX() == x && face.getModY() == y && face.getModZ() == z) return face;
        }
        throw new IllegalArgumentException("Not a block face: " + x + " " + y + " " + z);
    }

    /**
     * The wall spanning from this anchor to the given block, in whichever direction that is.
     *
     * <p>Sizes are always positive, so the anchor moves to whichever corner turns out to be the bottom left.
     * The block first clicked stays an edge either way.
     *
     * <p>Always call this on the original one-by-one anchor, never on the result: stretching a stretched wall
     * measures from an anchor that has already moved, so sizing crawls away from where it started.
     *
     * <p>Points off the plane are projected onto it rather than rejected, so looking at a block that sticks
     * out still picks the cell behind it instead of freezing the preview.
     */
    public WallLayout stretchedTo(int x, int y, int z) {
        int dx = x - anchorX;
        int dy = y - anchorY;
        int dz = z - anchorZ;

        int along = dx * right.getModX() + dy * right.getModY() + dz * right.getModZ();
        int above = dx * up.getModX() + dy * up.getModY() + dz * up.getModZ();

        int wide = clamp(Math.abs(along) + 1);
        int tall = clamp(Math.abs(above) + 1);
        // Growing the wrong way means the far corner is the bottom left, so step the anchor over to it.
        int backAlong = along < 0 ? wide - 1 : 0;
        int backAbove = above < 0 ? tall - 1 : 0;

        return new WallLayout(facing,
                anchorX - right.getModX() * backAlong - up.getModX() * backAbove,
                anchorY - right.getModY() * backAlong - up.getModY() * backAbove,
                anchorZ - right.getModZ() * backAlong - up.getModZ() * backAbove,
                wide, tall, right, up
        );
    }

    private static int clamp(int side) {
        return Math.max(1, Math.min(MAX_SIDE, side));
    }

    public WallLayout resized(int cols, int rows) {
        return new WallLayout(facing, anchorX, anchorY, anchorZ, clamp(cols), clamp(rows), right, up);
    }

    public int pixelWidth() {
        return cols * TILE;
    }

    public int pixelHeight() {
        return rows * TILE;
    }

    public int count() {
        return cols * rows;
    }

    public int blockX(int col, int row) {
        return anchorX + right.getModX() * col + up.getModX() * row;
    }

    public int blockY(int col, int row) {
        return anchorY + right.getModY() * col + up.getModY() * row;
    }

    public int blockZ(int col, int row) {
        return anchorZ + right.getModZ() * col + up.getModZ() * row;
    }

    /**
     * Where a tile's pixels live on the wall's surface.
     *
     * <p>Rows count up from the anchor and pixels count down from the top, so the row is flipped on
     * the way in - the bottom left block is the bottom left of the picture.
     */
    public int surfaceX(int col) {
        return col * TILE;
    }

    public int surfaceY(int row) {
        return (rows - 1 - row) * TILE;
    }

    /**
     * How far in front of the map a point is, in blocks. Zero on the map, positive on the viewer's side,
     * negative behind it.
     *
     * <p>The sign is the only thing separating someone looking at the picture from someone looking at the
     * back of the block it hangs on - both land on the same plane.
     */
    public double depthOf(double x, double y, double z) {
        return (x - faceX()) * facing.getModX()
                + (y - faceY()) * facing.getModY()
                + (z - faceZ()) * facing.getModZ();
    }

    /** The side of the anchor block the maps sit against. */
    private double faceX() {
        return anchorX + (facing.getModX() > 0 ? 1 : 0);
    }

    private double faceY() {
        return anchorY + (facing.getModY() > 0 ? 1 : 0);
    }

    private double faceZ() {
        return anchorZ + (facing.getModZ() > 0 ? 1 : 0);
    }

    /** A pixel on the wall's surface. */
    public record Aim(int x, int y) {
    }

    /**
     * Where a point in the world lands on the surface, or null if it misses the wall.
     *
     * <p>Meant to be fed a point on the wall's plane. The component along the face is ignored, so it does
     * not matter that the map hangs a whisker in front of the blocks it is measured against.
     */
    @Nullable
    public Aim aimedAt(double x, double y, double z) {
        return aimedAt(x, y, z, 0);
    }

    /**
     * The same, counting points up to {@code margin} pixels outside as the nearest edge.
     *
     * <p>The last row of pixels is a strip a fraction of a block wide, so a margin is what makes it
     * reachable: overshoot and keep drawing along the edge instead of falling off it.
     */
    @Nullable
    public Aim aimedAt(double x, double y, double z, int margin) {
        double across = (x - originX()) * right.getModX()
                + (y - originY()) * right.getModY()
                + (z - originZ()) * right.getModZ();
        double above = (x - originX()) * up.getModX()
                + (y - originY()) * up.getModY()
                + (z - originZ()) * up.getModZ();

        // Floored rather than cast, since truncation would fold the whole strip left of the wall onto -0.
        int px = (int) Math.floor(across * TILE);
        // Pixels count down from the top while blocks count up, so the vertical axis flips - across the
        // pixel range rather than the block range, or the bottom edge lands one row past the surface.
        int py = pixelHeight() - 1 - (int) Math.floor(above * TILE);

        if (px < -margin || px >= pixelWidth() + margin) return null;
        if (py < -margin || py >= pixelHeight() + margin) return null;

        return new Aim(Math.clamp(px, 0, pixelWidth() - 1), Math.clamp(py, 0, pixelHeight() - 1));
    }

    /**
     * The middle of a surface pixel, in world coordinates. The inverse of {@link #aimedAt}.
     *
     * <p>Needed because of the margin: an overshoot is pinned back onto the picture, so where a viewer is
     * treated as pointing is no longer where their sight line crossed the plane. Anything measuring against
     * the world - distance, or whether something is in the way - has to use this point, not that one.
     */
    public double pixelX(Aim aim) {
        return planeOriginX() + right.getModX() * across(aim) + up.getModX() * above(aim);
    }

    public double pixelY(Aim aim) {
        return planeOriginY() + right.getModY() * across(aim) + up.getModY() * above(aim);
    }

    public double pixelZ(Aim aim) {
        return planeOriginZ() + right.getModZ() * across(aim) + up.getModZ() * above(aim);
    }

    private static double across(Aim aim) {
        return (aim.x() + 0.5) / TILE;
    }

    private double above(Aim aim) {
        return (pixelHeight() - 1 - aim.y() + 0.5) / TILE;
    }

    /**
     * The corner both surface axes count away from, on the plane.
     *
     * <p>{@link #originX} says nothing useful about the facing axis, so that one coordinate comes from the
     * plane instead. Right, up and facing are perpendicular unit axes, so exactly one claims each.
     */
    private double planeOriginX() {
        return facing.getModX() != 0 ? faceX() : originX();
    }

    private double planeOriginY() {
        return facing.getModY() != 0 ? faceY() : originY();
    }

    private double planeOriginZ() {
        return facing.getModZ() != 0 ? faceZ() : originZ();
    }

    /**
     * Which of the wall's maps a point on the surface belongs to.
     *
     * <p>Numbered the way the maps are, along the bottom row first - so the flip between surface rows
     * counting down and block rows counting up happens here once rather than at every call site.
     */
    public int tileOf(Aim aim) {
        return tileOf(aim.x(), aim.y());
    }

    public int tileOf(int x, int y) {
        return (rows - 1 - tileRow(y)) * cols + tileCol(x);
    }

    /** Where that map's top left pixel sits on the surface, for turning a marker into map-local space. */
    public int tileOriginX(int x) {
        return tileCol(x) * TILE;
    }

    public int tileOriginY(int y) {
        return tileRow(y) * TILE;
    }

    private int tileCol(int x) {
        return Math.max(0, Math.min(cols - 1, x / TILE));
    }

    /** Counted from the top, like the surface it indexes into. */
    private int tileRow(int y) {
        return Math.max(0, Math.min(rows - 1, y / TILE));
    }

    /**
     * The corner of the wall both axes count away from.
     *
     * <p>Not the anchor block's own corner: a block spans a whole unit, so an axis running negative counts
     * from the far side of it.
     */
    private double originX() {
        return anchorX + (right.getModX() < 0 ? 1 : 0) + (up.getModX() < 0 ? 1 : 0);
    }

    private double originY() {
        return anchorY + (right.getModY() < 0 ? 1 : 0) + (up.getModY() < 0 ? 1 : 0);
    }

    private double originZ() {
        return anchorZ + (right.getModZ() < 0 ? 1 : 0) + (up.getModZ() < 0 ? 1 : 0);
    }

    /** Middle of the wall, so a wide one is not measured by one corner. */
    public double centerX() {
        return (blockX(0, 0) + blockX(cols - 1, rows - 1)) / 2.0 + 0.5;
    }

    public double centerY() {
        return (blockY(0, 0) + blockY(cols - 1, rows - 1)) / 2.0 + 0.5;
    }

    public double centerZ() {
        return (blockZ(0, 0) + blockZ(cols - 1, rows - 1)) / 2.0 + 0.5;
    }
}
