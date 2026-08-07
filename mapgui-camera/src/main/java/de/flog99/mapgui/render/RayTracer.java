package de.flog99.mapgui.render;

import java.util.List;

/**
 * Walks a ray per pixel through the block grid and shades what it hits.
 *
 * <p>A grid walk rather than a hierarchy over the geometry: the grid is implicit so there is nothing to build, each
 * step is a couple of adds, and blocks arrive strictly nearest first, which is what makes transparency correct
 * without sorting anything.
 *
 * <p>There is a hierarchy over the <i>emptiness</i>, which costs the ordering nothing. Where {@link EmptySpace} says
 * a cell holds nothing, the walk crosses it without asking the world about a single block - still one step at a time
 * and in the same order, so a ray arrives at a surface with the same numbers to the bit. {@code EmptySkipTest}
 * renders every awkward shape both ways and compares the frames.
 *
 * <p>Not thread safe: one instance per rendering thread, with the scratch arrays and the fragment list reused across
 * every pixel rather than 16384 rays each allocating a vector.
 */
public final class RayTracer {

    /**
     * What vanilla multiplies a face by for its direction, since there is no real lighting model to ask. Down
     * is darkest, up is full, and the two horizontal pairs differ so that a corner reads as a corner.
     */
    private static final float[] FACE_SHADE = new float[6];

    static {
        FACE_SHADE[Direction.DOWN.ordinal()] = 0.5f;
        FACE_SHADE[Direction.UP.ordinal()] = 1.0f;
        FACE_SHADE[Direction.NORTH.ordinal()] = 0.8f;
        FACE_SHADE[Direction.SOUTH.ordinal()] = 0.8f;
        FACE_SHADE[Direction.WEST.ordinal()] = 0.6f;
        FACE_SHADE[Direction.EAST.ordinal()] = 0.6f;
    }

    /** Where the brightness slider sits, on the client's own 0 to 1 scale. Not a setting: the curve is right. */
    private static final float GAMMA = 0.9f;

    /**
     * How far the darkest light is lifted off black, weighted so nearly all of it lands on the dark end.
     *
     * <p>The one place this parts company with the client on purpose. A screen renders a night in thousands of
     * near-blacks and your eye adapts; a map has 143 colors and a viewer adapted to whatever else is on screen, so
     * faithfully dark reads as a hole in the picture.
     *
     * <p>Weighted by {@code (1 - light)^SHADOW_FALLOFF} rather than applied as a floor, which is the whole point of
     * the shape: a floor is affine, so raising it enough to show a cave wall flattens the picture at noon to pay for
     * it. This does nothing at all at light 15.
     *
     * <p><b>The two move together.</b> The table has to stay non-decreasing - an unlit block drawing brighter than a
     * torchlit one is worse than either being dark - and the client's own curve is nearly flat across the bottom, so
     * a lift the dark end can absorb is small. At the old falloff of 2 the ceiling was 0.53, and 0.55 already drew
     * light 1 darker than light 0. Softening the falloff is what buys the headroom; at 1.5 the ceiling is near 0.7.
     * {@code LightTableTest} holds the line.
     */
    static final float SHADOW_LIFT = 0.6f;

    /** See {@link #SHADOW_LIFT}: lower spreads the lift further up the range, and too low inverts the table. */
    private static final double SHADOW_FALLOFF = 1.5;

    /**
     * Light level to multiplier: the client's own table with that lift applied, sixteen entries computed once so the
     * per-texel cost is an array read.
     *
     * <p>Reproduced rather than approximated because every guess at the shape is wrong visibly. It is not linear -
     * {@code l / (4 - 3l)} bends it steeply, so light 7 is a fifth of full and not a half - and then the brightness
     * slider blends toward a gentler curve and the whole table is pulled four percent toward grey.
     */
    private static final float[] LIGHT = lightTable(0);

    /** The same for a dimension that lights everything a little for nothing. A table rather than arithmetic per texel. */
    private static final float[] NETHER_LIGHT = lightTable(0.1f);

    private static final float[] END_LIGHT = lightTable(0.25f);

    /** Package-private for {@code LightTableTest}, which is what keeps {@link #SHADOW_LIFT} tunable safely. */
    static float[] lightTable(float ambient) {
        float[] table = new float[16];

        for (int level = 0; level < table.length; level++) {
            float share = level / 15f;
            // The client's own {@code LightTexture.getBrightness}: the curve, then lifted toward full by the
            // dimension's ambient light, which is what makes the Nether's floor visible at all.
            float curved = share / (4 - 3 * share);
            curved += ambient * (1 - curved);
            // The companion curve the slider blends toward, which lifts the dark end and leaves full light alone.
            float lifted = 1 - (float) Math.pow(1 - curved, 4);
            float lit = curved + (lifted - curved) * GAMMA;
            float client = Math.clamp(lit + (0.75f - lit) * 0.04f, 0, 1);
            table[level] = Math.min(1, client + SHADOW_LIFT * (float) Math.pow(1 - client, SHADOW_FALLOFF));
        }

        return table;
    }

    /** The client's table is indexed by level, so anything outside 0 to 15 is a bug rather than a dim room. */
    private float litBy(int level) {
        return lights[Math.clamp(level, 0, 15)];
    }

    /** Which table this frame reads, by how much light its dimension gives away. */
    private static float[] tableFor(float ambient) {
        if (ambient >= 0.2f) return END_LIGHT;

        return ambient >= 0.05f ? NETHER_LIGHT : LIGHT;
    }

    /**
     * How much nearer each successive element of one block is treated as being, in blocks - enough to order two
     * coincident faces and far too little to reorder them against a neighbouring block.
     */
    private static final double DECAL_BIAS = 1e-4;

    /** Where a leaf's gaps start closing, in blocks: roughly where one of its texels drops below one pixel. */
    static final double CANOPY_NEAR = 16;

    /** Where a canopy is solid. Past this a forest is a surface rather than a thousand leaves with sky between. */
    static final double CANOPY_FAR = 50;

    /**
     * Where water fog begins, in blocks, and vanilla's own number rather than a nudge for effect. Starting behind the
     * camera is what tints everything in the frame rather than only the distance: a block against the lens is already
     * a quarter faded. The far end comes from the world, since two biomes state murkier water than the rest.
     */
    private static final double WATER_FOG_START = -8;

    /**
     * The client's own three numbers for a dimension whose air is thick: {@code FogRenderer} runs its terrain fog from
     * a twentieth of the render distance to half of it, with the distance capped at 192 blocks first.
     */
    private static final double FOGGY_AIR_START = 0.05;

    private static final double FOGGY_AIR_END = 0.5;

    private static final double FOGGY_AIR_CAP = 192;

    private final Textures atlas;
    private final EntityTracer entityTracer;

    /** Off only so that a test can render the same scene both ways and compare, since it must come out identical. */
    private final boolean skipEmpty;

    private final double[] direction = new double[3];
    private final Fragments fragments = new Fragments();

    /**
     * Scratch for the slab test, reused rather than allocated: a block with several boxes is tested once per pixel
     * that sees it, so allocating here is tens of thousands of three-element arrays a frame.
     */
    private final double[] slabOrigin = new double[3];
    private final double[] slabDirection = new double[3];
    private final double[] slabLow = new double[3];
    private final double[] slabHigh = new double[3];

    /** Set by {@link #enterBox}, which finds the face and the point along with the distance it returns. */
    private Direction boxFace;
    private double boxHitX;
    private double boxHitY;
    private double boxHitZ;

    /** The frame being traced, so that the lazy sky does not have to be threaded through every shading call. */
    private CameraView frameView;

    /** Asked for once per frame rather than once per ray, since a world hands back the same structure every time. */
    private EmptySpace frameEmpty = EmptySpace.NONE;

    private int skyHere;
    private boolean skyKnown;
    private boolean fog;
    private double fogStart;
    private double fogEnd;

    /** What the frame fades into instead of the sky, and 0 for a camera that is not under water. */
    private int submerged;

    /** The light table this frame reads, which depends on the dimension and so cannot be static. */
    private float[] lights = LIGHT;

    public RayTracer(Textures atlas) {
        this(atlas, true);
    }

    RayTracer(Textures atlas, boolean skipEmpty) {
        this.atlas = atlas;
        this.skipEmpty = skipEmpty;
        this.entityTracer = new EntityTracer(atlas);
    }

    /**
     * Renders one frame as packed ARGB, row by row. ARGB rather than palette indices, so quantizing stays outside
     * this module - it needs the map palette, which lives with the server.
     *
     * @param out {@code width * height} long
     */
    public void render(VoxelSource world, CameraView view, int width, int height, int[] out) {
        render(world, view, List.of(), width, height, out);
    }

    /**
     * The same, with entities. Blocks first, then only the entities whose screen rect covers the pixel; ordering is
     * {@link Fragments}, which sorts by depth, so an entity behind a wall contributes nothing without a check.
     */
    public void render(VoxelSource world, CameraView view, List<EntitySnapshot> entities, int width, int height, int[] out) {
        render(world, view, entities, width, height, out, 0, height);
    }

    /**
     * One horizontal band of a frame, so several tracers can share the work. Bands rather than tiles because a row is
     * contiguous in {@code out}, so two threads never write the same cache line, and everything a band reads is
     * immutable or its own.
     *
     * @param fromRow inclusive, {@code toRow} exclusive
     */
    public void render(VoxelSource world, CameraView view, List<EntitySnapshot> entities,
                       int width, int height, int[] out, int fromRow, int toRow) {

        fog = view.fog();
        // Only the far half fades, so nearby detail is untouched and the distance cap stops reading as a wall.
        fogStart = view.maxDistance() * 0.55;
        fogEnd = view.maxDistance();

        // The Nether's air hides distance on its own. Always on rather than optional: terrain drawn sharp to the
        // horizon there does not read as the Nether at all.
        if (world.sky().foggyAir()) {
            fog = true;
            fogStart = view.maxDistance() * FOGGY_AIR_START;
            fogEnd = Math.min(view.maxDistance(), FOGGY_AIR_CAP) * FOGGY_AIR_END;
        }

        // Water wins over the air's fog, being the nearer medium, and it starts before the camera.
        lights = tableFor(world.sky().ambientLight());
        submerged = world.submergedIn();
        if (submerged != 0) {
            fog = true;
            fogStart = WATER_FOG_START;
            fogEnd = Math.min(view.maxDistance(), world.submergedSight());
        }

        frameView = view;
        frameEmpty = skipEmpty ? world.emptySpace() : EmptySpace.NONE;
        CameraView.Frame frame = view.frame();
        EntityScreen screen = entities.isEmpty() ? null : new EntityScreen(entities, view, width, height);

        for (int py = fromRow; py < toRow; py++) {
            int[] row = screen == null ? null : screen.row(py);

            for (int px = 0; px < width; px++) {
                frame.direction(px, py, width, height, direction);

                // Left uncomputed until something wants it: a sky is a gradient, a glow, a star hash, two celestial
                // discs and a cloud sheet, and a pixel behind an opaque near surface never asks.
                skyKnown = false;

                fragments.reset();
                traceBlocks(world, view, direction[0], direction[1], direction[2]);

                if (row != null) {
                    traceEntities(world, screen, row, px, py, view, direction[0], direction[1], direction[2]);
                }

                // An opaque fragment means the background is multiplied by nothing, so the sky can stay unasked for.
                int background = fragments.opaqueDistance() == Float.MAX_VALUE ? backdrop(world) : 0;
                out[py * width + px] = fragments.composite(background);
            }
        }
    }

    /**
     * What this ray ends in: the sky, or the water the camera is under. Under water the fog closes long before the
     * surface does, so a ray that hits nothing ends in water rather than in a sunset.
     */
    private int backdrop(VoxelSource world) {
        return submerged != 0 ? submerged : sky(world);
    }

    /** The sky this ray is pointed at, worked out at most once per ray and only if something wants it. */
    private int sky(VoxelSource world) {
        if (!skyKnown) {
            skyHere = world.sky().colorFor(frameView.y(), direction[0], direction[1], direction[2]);
            skyKnown = true;
        }
        return skyHere;
    }

    private void traceEntities(VoxelSource world, EntityScreen screen, int[] row, int px, int py, CameraView view,
                               double dx, double dy, double dz) {

        double limit = Math.min(fragments.opaqueDistance(), view.maxDistance());

        for (int index : row) {
            if (!screen.covers(index, px, py)) {
                continue;
            }

            if (entityTracer.hit(screen.entity(index), view.x(), view.y(), view.z(), dx, dy, dz, limit)) {
                double at = entityTracer.distance();
                int lit = litEntity(world, entityTracer.color(), entityTracer.face(),
                        view.x() + dx * at, view.y() + dy * at, view.z() + dz * at);
                if (fog && at > fogStart) {
                    lit = fogged(lit, at, backdrop(world));
                }

                // Opaque: an entity texel is either drawn or it is not, so it always stops the ray.
                fragments.add(0xFF000000 | lit & 0xFFFFFF, (float) at);
                limit = Math.min(limit, at);
            }
        }
    }

    /**
     * An entity texel shaded by where it is standing - drawn at its texture's own brightness a mob is lit for noon
     * wherever it is, and a cave full of fully lit zombies reads as pasted on. The light is read at the hit point,
     * which is the air the model occupies rather than the block underneath it.
     */
    private int litEntity(VoxelSource world, int texel, Direction face, double atX, double atY, double atZ) {
        int light = world.lightAt((int) Math.floor(atX), (int) Math.floor(atY), (int) Math.floor(atZ));
        float factor = FACE_SHADE[face.ordinal()] * litBy(light);

        int red = Math.round((texel >> 16 & 0xFF) * factor);
        int green = Math.round((texel >> 8 & 0xFF) * factor);
        int blue = Math.round((texel & 0xFF) * factor);
        return red << 16 | green << 8 | blue;
    }

    /** One ray through the blocks, adding whatever it passes to {@link #fragments}. */
    private void traceBlocks(VoxelSource world, CameraView view, double dx, double dy, double dz) {
        double originX = view.x();
        double originY = view.y();
        double originZ = view.z();

        int blockX = (int) Math.floor(originX);
        int blockY = (int) Math.floor(originY);
        int blockZ = (int) Math.floor(originZ);

        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;

        double deltaX = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);

        double nextX = boundary(originX, blockX, dx, deltaX);
        double nextY = boundary(originY, blockY, dy, deltaY);
        double nextZ = boundary(originZ, blockZ, dz, deltaZ);

        // The camera's own block is skipped: it was entered through no face, and standing inside a block should
        // not paint that block's inside over the whole frame.
        Direction entered = null;
        double travelled = 0;
        double range = view.maxDistance();

        // The world's own bounds, read once. They are constants of a capture and this is the innermost loop there is.
        int ceiling = world.highestBlock();
        int floor = world.minY();
        int roof = world.maxY();

        // Last column asked about, so a ray climbing through one does not re-read its heightmap per block.
        int columnX = Integer.MIN_VALUE;
        int columnZ = Integer.MIN_VALUE;
        int columnTop = 0;

        // The empty cell the ray is crossing, held as the first block past it on each axis. Blocks are still stepped
        // one at a time, so leaving is an exact integer match on whichever axis leaves first - and the arithmetic the
        // walk carries is untouched, which is what makes the skip cost nothing in quality.
        EmptySpace empty = frameEmpty;
        boolean crossingEmpty = false;
        int leaveX = 0;
        int leaveY = 0;
        int leaveZ = 0;

        // Last cell asked about, so a ray inside an occupied cell asks once for it rather than once per block.
        int askedX = Integer.MIN_VALUE;
        int askedY = Integer.MIN_VALUE;
        int askedZ = Integer.MIN_VALUE;

        while (travelled <= range) {
            if (entered != null) {
                if (blockY > ceiling && dy >= 0) break;
                if (blockY < floor && dy <= 0) break;

                if (!crossingEmpty || blockX == leaveX || blockY == leaveY || blockZ == leaveZ) {
                    crossingEmpty = false;

                    int cellX = blockX >> EmptySpace.CELL;
                    int cellY = blockY >> EmptySpace.CELL;
                    int cellZ = blockZ >> EmptySpace.CELL;
                    if (cellX != askedX || cellY != askedY || cellZ != askedZ) {
                        askedX = cellX;
                        askedY = cellY;
                        askedZ = cellZ;

                        int shift = empty.shiftAt(blockX, blockY, blockZ);
                        if (shift != 0) {
                            int size = 1 << shift;
                            leaveX = (blockX & -size) + (stepX > 0 ? size : -1);
                            leaveY = (blockY & -size) + (stepY > 0 ? size : -1);
                            leaveZ = (blockZ & -size) + (stepZ > 0 ? size : -1);
                            crossingEmpty = true;
                        }
                    }
                }

                if (!crossingEmpty) {
                    if (blockX != columnX || blockZ != columnZ) {
                        columnX = blockX;
                        columnZ = blockZ;
                        columnTop = world.columnTop(blockX, blockZ);
                    }

                    // Above everything in this column there is nothing to ask about, and asking is a chunk lookup and
                    // a block read where stepping on is a few adds.
                    if (blockY <= columnTop && blockY >= floor && blockY <= roof) {
                        BakedState state = world.stateAt(blockX, blockY, blockZ);
                        if (!state.isEmpty() && !sample(world, state, entered, blockX, blockY, blockZ, originX, originY, originZ, dx, dy, dz, travelled)) {
                            break;
                        }
                    }
                }
            }

            if (nextX < nextY && nextX < nextZ) {
                blockX += stepX;
                travelled = nextX;
                nextX += deltaX;
                entered = stepX > 0 ? Direction.WEST : Direction.EAST;
            } else if (nextY < nextZ) {
                blockY += stepY;
                travelled = nextY;
                nextY += deltaY;
                entered = stepY > 0 ? Direction.DOWN : Direction.UP;
            } else {
                blockZ += stepZ;
                travelled = nextZ;
                nextZ += deltaZ;
                entered = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
            }
        }
    }

    /** Distance along the ray to the first block boundary on one axis. */
    private static double boundary(double origin, int block, double direction, double delta) {
        if (direction == 0) return Double.MAX_VALUE;

        double fraction = direction > 0 ? block + 1 - origin : origin - block;
        return fraction * delta;
    }

    /**
     * Adds whatever this block contributes, and says whether the ray carries on.
     *
     * <p>Decided per texel rather than per block: a cutout is transparent only where its texture is, so leaves stop
     * a ray through a leaf and pass one through the gap. The block's class is the fast path, since an opaque block
     * needs no alpha test at all.
     *
     * <p>An opaque texel ends the ray but not this loop. A block's elements are in the order the model listed them
     * rather than in depth order, so leaving early drops whatever is <i>in front of</i> the opaque one - which is the
     * water in a waterlogged stair.
     */
    private boolean sample(VoxelSource world, BakedState state, Direction entered,
                           int blockX, int blockY, int blockZ,
                           double originX, double originY, double originZ,
                           double dx, double dy, double dz, double travelled) {

        boolean stopped = false;
        int order = 0;

        for (BakedElement element : state.elements()) {
            Direction face = entered;
            double hit = travelled;
            double localX;
            double localY;
            double localZ;

            if (element.isFullBlock()) {
                localX = (originX + dx * hit - blockX) * 16;
                localY = (originY + dy * hit - blockY) * 16;
                localZ = (originZ + dz * hit - blockZ) * 16;
            } else {
                hit = enterBox(element, blockX, blockY, blockZ, originX, originY, originZ, dx, dy, dz);
                if (Double.isNaN(hit)) {
                    continue;
                }
                face = boxFace;
                localX = boxHitX;
                localY = boxHitY;
                localZ = boxHitZ;
            }

            BakedFace drawn = element.face(face);
            if (drawn == null || culled(world, state, drawn, blockX, blockY, blockZ)) {
                continue;
            }

            int texel = texel(element, drawn, face, localX, localY, localZ);
            int alpha = state.alpha() == BakedState.Alpha.OPAQUE ? 255 : texel >>> 24;
            if (alpha == 0) {
                // A gap in a distant canopy is smaller than the pixel looking through it, so what is behind it gets a
                // share of that pixel rather than one of its own. Filling the gap with the leaf color is that share.
                float fill = state.leaves() ? canopyFill(hit) : 0;
                if (fill <= 0) {
                    continue;
                }

                texel = atlas.get(drawn.texture()).average();
                alpha = Math.round(255 * fill);
            }

            int shaded = shade(world, texel, drawn, face, blockX, blockY, blockZ, element.shade(), element.emission());
            if (fog && hit > fogStart) {
                shaded = fogged(shaded, hit, backdrop(world));
            }
            // Later elements composite in front, which the depth sort cannot work out for itself: a grass block is a
            // cube of dirt with a coincident cube carrying the green fringe, and at equal depth the dirt won.
            if (!fragments.add(alpha << 24 | shaded & 0xFFFFFF, (float) Math.max(0, hit - order++ * DECAL_BIAS))) {
                return false;
            }

            stopped |= alpha == 255;
        }

        return !stopped && !fragments.isFull();
    }

    /**
     * Whether the neighbour hides this face, which is what {@code cullface} in a model is for. Three cases: a face
     * against a solid full block, a face between two blocks holding the same water, and a translucent block against
     * itself, which is what keeps a pane of glass one pane rather than stacked layers of blue.
     *
     * <p>Water needs its own rule rather than the identity one, since a source, a flowing block and a waterlogged
     * stair are three states holding the same water - comparing states leaves seams at the edges of a pool.
     */
    private static boolean culled(VoxelSource world, BakedState state, BakedFace drawn, int blockX, int blockY, int blockZ) {
        Direction against = drawn.cull();
        if (against == null) return false;

        BakedState neighbour = world.stateAt(blockX + against.dx(), blockY + against.dy(), blockZ + against.dz());
        if (neighbour.isEmpty()) return false;

        if (neighbour.fullCube() && neighbour.alpha() == BakedState.Alpha.OPAQUE) return true;

        if (drawn.fluid() && neighbour.water()) return true;

        // Identity is enough: states are cached per state string, so two panes of the same glass are one object.
        return neighbour == state && state.alpha() != BakedState.Alpha.OPAQUE;
    }

    /**
     * Where the ray enters one box of a model, or NaN if it misses, with the side it came in through left in
     * {@link #boxFace}.
     *
     * <p>The face is the axis whose slab produced the entry distance rather than whichever plane the hit point ended
     * up nearest. Invisible on a cube and decisive on a flat one: on a zero-thickness plane the nearest is arbitrary,
     * and picking it reported the underside of grass, which then took its light from the block below and came out
     * black.
     *
     * <p>A box with its own {@link ElementRotation} is not axis-aligned, so the ray is bent into the space the box
     * was authored in and the same slab test runs there - which is also the only space its uv means anything in.
     */
    private double enterBox(BakedElement element, int blockX, int blockY, int blockZ,
                            double originX, double originY, double originZ,
                            double dx, double dy, double dz) {

        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int enterAxis = -1;
        boolean enterFromLow = true;

        double[] origins = slabOrigin;
        double[] directions = slabDirection;
        double[] lows = slabLow;
        double[] highs = slabHigh;

        origins[0] = originX - blockX;
        origins[1] = originY - blockY;
        origins[2] = originZ - blockZ;
        directions[0] = dx;
        directions[1] = dy;
        directions[2] = dz;
        lows[0] = element.fromX() / 16.0;
        lows[1] = element.fromY() / 16.0;
        lows[2] = element.fromZ() / 16.0;
        highs[0] = element.toX() / 16.0;
        highs[1] = element.toY() / 16.0;
        highs[2] = element.toZ() / 16.0;

        if (element.rotation() != null) {
            untwist(element.rotation(), origins, directions);
        }

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(directions[axis]) < 1e-12) {
                if (origins[axis] < lows[axis] || origins[axis] > highs[axis]) return Double.NaN;
                continue;
            }

            double inverse = 1 / directions[axis];
            double first = (lows[axis] - origins[axis]) * inverse;
            double second = (highs[axis] - origins[axis]) * inverse;

            double near = Math.min(first, second);
            if (near > enter) {
                enter = near;
                enterAxis = axis;
                // From the direction, not from which distance came out smaller: on a flat box both distances are the
                // same number, so comparing them picks the low side every time. A flowerbed is a zero-thickness
                // horizontal plane, and it was drawn at the underside's 0.5 shade however you looked at it.
                enterFromLow = directions[axis] > 0;
            }
            exit = Math.min(exit, Math.max(first, second));
        }

        if (exit < enter || exit < 0 || enterAxis < 0) return Double.NaN;

        boxFace = switch (enterAxis) {
            case 0 -> enterFromLow ? Direction.WEST : Direction.EAST;
            case 1 -> enterFromLow ? Direction.DOWN : Direction.UP;
            default -> enterFromLow ? Direction.NORTH : Direction.SOUTH;
        };

        double at = Math.max(enter, 0);
        boxHitX = (origins[0] + directions[0] * at) * 16;
        boxHitY = (origins[1] + directions[1] * at) * 16;
        boxHitZ = (origins[2] + directions[2] * at) * 16;
        return at;
    }

    /**
     * The inverse of an element rotation, applied to a ray in block units. Forward the box is turned and then
     * widened, so coming back it is narrowed and then turned the other way. Neither touches the ray's parameter, so
     * the distance the slab test hands back is still a distance in the world.
     */
    private static void untwist(ElementRotation turn, double[] origins, double[] directions) {
        double aboutX = turn.originX() / 16.0;
        double aboutY = turn.originY() / 16.0;
        double aboutZ = turn.originZ() / 16.0;

        origins[0] -= aboutX;
        origins[1] -= aboutY;
        origins[2] -= aboutZ;

        double shrink = turn.shrink();
        if (shrink != 1) {
            for (int axis = 0; axis < 3; axis++) {
                if (axis != turn.axis()) {
                    origins[axis] *= shrink;
                    directions[axis] *= shrink;
                }
            }
        }

        double cos = Math.cos(Math.toRadians(turn.angle()));
        double sin = Math.sin(Math.toRadians(turn.angle()));
        unrotate(origins, turn.axis(), cos, sin);
        unrotate(directions, turn.axis(), cos, sin);

        origins[0] += aboutX;
        origins[1] += aboutY;
        origins[2] += aboutZ;
    }

    /** One vector turned back about one axis, right-handed, matching what the client builds the geometry with. */
    private static void unrotate(double[] vector, int axis, double cos, double sin) {
        switch (axis) {
            case 0 -> {
                double y = vector[1] * cos + vector[2] * sin;
                double z = -vector[1] * sin + vector[2] * cos;
                vector[1] = y;
                vector[2] = z;
            }
            case 1 -> {
                double x = vector[0] * cos - vector[2] * sin;
                double z = vector[0] * sin + vector[2] * cos;
                vector[0] = x;
                vector[2] = z;
            }
            default -> {
                double x = vector[0] * cos + vector[1] * sin;
                double y = -vector[0] * sin + vector[1] * cos;
                vector[0] = x;
                vector[1] = y;
            }
        }
    }

    /** The texel under the hit point. The per-face mapping is {@link BakedFace#u} and {@link BakedFace#v}. */
    private int texel(BakedElement element, BakedFace drawn, Direction face, double localX, double localY, double localZ) {
        Direction modelFace = face;
        double mx = localX;
        double my = localY;
        double mz = localZ;

        // Back into the space the uv was written in: the geometry was turned at bake time and the face rects were
        // not. Undone by turning the rest of the way round rather than by turning back, since turning back 4 - n
        // times is a half circle out for 90 and 270 - which put every east-west bed and door the wrong way round.
        if (element.rotated()) {
            modelFace = face.unrotate(element.rotX(), element.rotY());
            for (int quarter = 0; quarter < 4 - Math.floorMod(element.rotY(), 360) / 90; quarter++) {
                double turnedX = 16 - mz;
                double turnedZ = mx;
                mx = turnedX;
                mz = turnedZ;
            }
            for (int quarter = 0; quarter < 4 - Math.floorMod(element.rotX(), 360) / 90; quarter++) {
                double turnedY = mz;
                double turnedZ = 16 - my;
                my = turnedY;
                mz = turnedZ;
            }
        }

        double u = BakedFace.u(modelFace, mx, my, mz);
        double v = BakedFace.v(modelFace, mx, my, mz);

        // Into the rect the model states for this face, which is how a slab takes the bottom half of a texture.
        float su = (float) (drawn.u1() + u / 16 * (drawn.u2() - drawn.u1()));
        float sv = (float) (drawn.v1() + v / 16 * (drawn.v2() - drawn.v1()));
        return atlas.get(drawn.texture()).sample(su, sv, drawn.rotation());
    }

    /**
     * How much of a leaf gap is filled in at this distance, 0 to 1. A resolution problem rather than a stylistic
     * one: past {@link #CANOPY_NEAR} a leaf texel is smaller than a pixel, so a gap has no pixel of its own to be
     * seen through and drawing it as all gap makes a distant forest read as sky with twigs in it.
     *
     * <p>It closes over the texture's own average, which is what the client arrives at by mipmapping.
     */
    private static float canopyFill(double distance) {
        if (distance <= CANOPY_NEAR) return 0;

        return (float) Math.min(1, (distance - CANOPY_NEAR) / (CANOPY_FAR - CANOPY_NEAR));
    }

    /** Fades toward the sky over the far stretch, so the distance cap is a haze rather than an edge. */
    private int fogged(int argb, double distance, int sky) {
        if (distance <= fogStart) return argb;

        float amount = (float) Math.min(1, (distance - fogStart) / (fogEnd - fogStart));
        int red = Math.round((argb >> 16 & 0xFF) * (1 - amount) + (sky >> 16 & 0xFF) * amount);
        int green = Math.round((argb >> 8 & 0xFF) * (1 - amount) + (sky >> 8 & 0xFF) * amount);
        int blue = Math.round((argb & 0xFF) * (1 - amount) + (sky & 0xFF) * amount);
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /** Face direction, then light, then the block's tint if it has one. */
    private int shade(VoxelSource world, int texel, BakedFace drawn, Direction face, int blockX, int blockY, int blockZ, boolean shade, int emission) {
        float factor = shade ? FACE_SHADE[face.ordinal()] : 1f;

        // The air the ray came through, since light inside a solid block is zero and lighting a wall by it makes
        // every wall black. The block's own is the fallback for geometry inside an otherwise empty block.
        int neighbour = world.lightAt(blockX + face.dx(), blockY + face.dy(), blockZ + face.dz());
        int light = Math.max(neighbour, world.lightAt(blockX, blockY, blockZ));
        // A box stating its own emission is lit by that where it is brighter, which is what glows a firefly bush.
        factor *= litBy(Math.max(light, emission));

        int red = (int) ((texel >> 16 & 0xFF) * factor);
        int green = (int) ((texel >> 8 & 0xFF) * factor);
        int blue = (int) ((texel & 0xFF) * factor);

        if (drawn.tint() != Tints.NONE) {
            int fixed = Tints.fixed(drawn.tint());
            int tint = fixed != 0 ? fixed : world.tintAt(blockX, blockY, blockZ, drawn.tint());
            red = red * (tint >> 16 & 0xFF) / 255;
            green = green * (tint >> 8 & 0xFF) / 255;
            blue = blue * (tint & 0xFF) / 255;
        }

        return (texel & 0xFF000000) | red << 16 | green << 8 | blue;
    }
}
