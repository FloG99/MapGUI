package de.flog99.mapgui.render;

import java.util.Arrays;
import java.util.List;

/**
 * Walks a ray per pixel through the block grid and shades what it hits.
 *
 * <p>A grid walk rather than a hierarchy over the geometry: the grid is implicit so there is nothing to build, each
 * step is a couple of adds, and blocks arrive strictly nearest first, which is what makes transparency correct
 * without sorting anything.
 *
 * <p>There is a hierarchy over the <i>emptiness</i>, which costs the ordering nothing. Where {@link EmptySpace} says
 * a cell holds nothing the walk crosses it in one go, and the same again for the air over open ground, which
 * {@link VoxelSource#maxTopIn} bounds. Nothing is sampled in between either way, so a ray comes out of a jump at the
 * block stepping there would have reached and having seen the same nothing on the way. {@code EmptySkipTest} and
 * {@code HeightJumpTest} render every awkward shape both ways and compare the frames.
 *
 * <p>Not thread safe: one instance per rendering thread, with the scratch arrays and the fragment list reused across
 * every pixel rather than 16384 rays each allocating a vector.
 */
public final class RayCaster {

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

    /**
     * Where the brightness slider sits, on the client's own 0 to 1 scale.
     *
     * <p>All the way up, which is where most people leave it - so a capture matches what a player at 100% brightness is
     * actually looking at.
     */
    private static final float GAMMA = 1.0f;

    /**
     * How far the darkest light is lifted off black by default, weighted so nearly all of it lands on the dark end.
     *
     * <p>A setting rather than a constant - {@code camera.shadow-lift} - because it is a real preference, and it was
     * settled twice in opposite directions before that was admitted. A screen renders a night in thousands of near-blacks
     * and your eye adapts to them, while a map has 143 colours and a viewer whose eye is adapted to whatever else is on
     * their screen. So a <i>faithful</i> dark can read as a hole in the picture rather than as a cave, and how much of
     * that anybody wants is not something this file can know.
     *
     * <p>Both ends were tried and both were wrong. At <b>0.6</b>, where this began, a wholly unlit block drew at 0.60 of
     * its own texture where the client draws it at 0.03 - an unlit room came out nearly as bright as a lit one, which is
     * not a legible cave, it is no cave at all. At <b>0</b>, which is exactly what the client does, that same block is 4
     * of 255 on stone and a dark room reads as a black rectangle.
     *
     * <p>The default is a fifth of the way up instead: an unlit block lands at 28 of 255, dark but legible, while the
     * bright end barely moves - light 11 goes from 0.872 to 0.881, so a lit room is still the lit room the client draws.
     *
     * <p>The falloff moves with it. The table has to stay non-decreasing - an unlit block drawing brighter than a
     * torchlit one is worse than either being dark - and the client's own curve is nearly flat across the bottom, so
     * there is a ceiling: at a falloff of 2, 0.55 already drew light 1 darker than light 0. {@code LightTableTest} holds
     * that line whatever these are set to.
     */
    public static final float SHADOW_LIFT = 0.2f;

    /** See {@link #SHADOW_LIFT}: lower spreads the lift further up the range, and too low inverts the table. */
    private static final double SHADOW_FALLOFF = 1.5;

    /**
     * Light level to multiplier: the client's own table with the shadow lift applied, sixteen entries computed once so
     * the per-texel cost is an array read.
     *
     * <p>Reproduced rather than approximated because every guess at the shape is visibly wrong. It is not linear -
     * {@code l / (4 - 3l)} bends it steeply, so light 7 is a fifth of full and not a half - and then the brightness
     * slider blends toward a gentler curve and the whole table is pulled four percent toward grey.
     *
     * <p>Three of them, one per kind of dimension, and held per caster rather than statically now that the lift they are
     * built with is a server's to choose. Sixteen floats each, built once per tracing thread.
     */
    private record Lights(float[] overworld, float[] nether, float[] end) {

        static Lights lifted(float lift) {
            return new Lights(lightTable(0, lift), lightTable(0.1f, lift), lightTable(0.25f, lift));
        }

        /** Which of them a frame reads, by how much light its dimension gives away for nothing. */
        float[] forAmbient(float ambient) {
            if (ambient >= 0.2f) return end;

            return ambient >= 0.05f ? nether : overworld;
        }
    }

    /** Package-private for {@code LightTableTest}, which is what keeps the lift tunable safely. */
    static float[] lightTable(float ambient, float lift) {
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
            table[level] = Math.min(1, client + lift * (float) Math.pow(1 - client, SHADOW_FALLOFF));
        }

        return table;
    }

    /** The client's table is indexed by level, so anything outside 0 to 15 is a bug rather than a dim room. */
    private float litBy(int level) {
        return lights[Math.clamp(level, 0, 15)];
    }

    /**
     * How much nearer each successive element of one block is treated as being, in blocks - enough to order two
     * coincident faces and far too little to reorder them against a neighbouring block.
     */
    private static final double DECAL_BIAS = 1e-4;

    /**
     * Where water fog begins, in blocks, and vanilla's own number rather than a nudge for effect. Starting behind the
     * camera is what tints everything in the frame rather than only the distance: a block against the lens is already
     * a quarter faded. The far end comes from the world, since two biomes state murkier water than the rest.
     */
    private static final double WATER_FOG_START = -8;

    /**
     * How far back from the far edge the haze reaches, in blocks, and vanilla's own arithmetic:
     * {@code FogRenderer} fades over {@code clamp(renderDistance / 10, 4, 64)} blocks and leaves everything nearer
     * alone.
     *
     * <p>A tenth rather than a share of the view is the whole point. The overworld's own fog runs to a thousand
     * blocks and is nothing a photograph reaches, so the only haze there is this one - it is not weather, it is the
     * edge of what has been drawn being hidden. Faded over the far half instead, as it was, a 96 block capture
     * starts going white at 53 blocks, which is the middle of the shot.
     */
    private static double taperOf(int maxDistance) {
        return Math.clamp(maxDistance / TAPER_SHARE, TAPER_MIN, TAPER_MAX);
    }

    private static final double TAPER_SHARE = 10;

    private static final double TAPER_MIN = 4;

    private static final double TAPER_MAX = 64;

    /**
     * The client's own three numbers for a dimension whose air is thick: {@code FogRenderer} runs its terrain fog from
     * a twentieth of the render distance to half of it, with the distance capped at 192 blocks first.
     */
    private static final double FOGGY_AIR_START = 0.05;

    private static final double FOGGY_AIR_END = 0.5;

    private static final double FOGGY_AIR_CAP = 192;

    private final Textures atlas;
    private final Canopy canopy;
    private final EntityTracer entityTracer;

    /** Off only so that a test can render the same scene both ways and compare, since it must come out identical. */
    private final boolean skipEmpty;

    /** The same, for {@link #hiddenByItsOwnKind}, which is the other place a frame is drawn by not looking. */
    private final boolean skipSameKind;

    private final double[] direction = new double[3];
    private final Fragments fragments = new Fragments();

    /**
     * The blocks this caster has already looked at, which is most of the ones it is about to be asked for again.
     *
     * <p>Per caster, so per tracing thread, and pointed at the frame's world at the top of {@link #render}. See
     * {@link SeenBlocks}: reading a block out of a chunk snapshot copies it, and the fluid surfaces alone ask about the
     * same nine positions sixteen times over.
     */
    private final SeenBlocks seen = new SeenBlocks();

    /**
     * Scratch for the slab test, reused rather than allocated: a block with several boxes is tested once per pixel
     * that sees it, so allocating here is tens of thousands of three-element arrays a frame.
     */
    private final double[] slabOrigin = new double[3];
    private final double[] slabDirection = new double[3];
    private final double[] slabLow = new double[3];
    private final double[] slabHigh = new double[3];

    /** One per traced position, which is plenty: a ray meets a handful of fluid blocks and neighbouring rays the same ones. */
    private static final int FLUID_SLOTS = 1024;

    /** No packed position is negative, so this is a slot nothing can match - including the origin, which packs to 0. */
    private static final long NO_POSITION = -1;

    private final long[] fluidKeys = new long[FLUID_SLOTS];
    private final int[] fluidCorners = new int[FLUID_SLOTS];
    private final float[] fluidFlows = new float[FLUID_SLOTS];

    /**
     * The same again for tints, on the same argument: neighbouring pixels look at the same few blocks, and a tint
     * is a biome lookup rather than a field of the state - {@link BiomeBlend} makes it four of them.
     *
     * <p>Worth its own table rather than being folded into the fluid one because most of what it answers for is not
     * fluid at all: a hillside of grass and leaves is where nearly every tinted pixel in a frame comes from.
     */
    private static final int TINT_SLOTS = 1024;

    private final long[] tintKeys = new long[TINT_SLOTS];
    private final int[] tintValues = new int[TINT_SLOTS];

    /** Set by {@link #enterBox}, which finds the face and the point along with the distance it returns. */
    private Direction boxFace;
    private double boxHitX;
    private double boxHitY;
    private double boxHitZ;

    /** The frame being traced, so that the lazy sky does not have to be threaded through every shading call. */
    private CameraView frameView;

    /**
     * Which face a clipped ray enters its first block through, or null for a frame that is not clipped.
     *
     * <p>The walk skips the block it starts in, because a camera standing inside one should not have that block's inside
     * painted over the frame. A <b>clipped</b> frame does not start where the camera is: it starts where the ray crosses
     * the plane, which is a point in the middle of the room, and the block it lands in is an ordinary block that happens to
     * be first.
     *
     * <p>Skipping it hid everything within a block of a mirror's glass - a chest pushed against one, a flower on the shelf
     * below it, the snow on the floor under one on a wall - which is a whole layer of the room, and the layer nearest the
     * viewer at that.
     *
     * <p>So it is walked like any other, and the face it was entered through is the one the plane lies on: rays cross
     * along the plane's normal, so a mirror facing east has them coming in through that block's west face. Read off the
     * dominant axis of the normal, which for the block face a mirror hangs on is exact.
     */
    private Direction crossedInto;

    private static Direction crossedInto(CameraView.ClipPlane plane) {
        double alongX = Math.abs(plane.normalX());
        double alongY = Math.abs(plane.normalY());
        double alongZ = Math.abs(plane.normalZ());

        if (alongX >= alongY && alongX >= alongZ) return plane.normalX() > 0 ? Direction.WEST : Direction.EAST;
        if (alongY >= alongZ) return plane.normalY() > 0 ? Direction.DOWN : Direction.UP;

        return plane.normalZ() > 0 ? Direction.NORTH : Direction.SOUTH;
    }

    /** Asked for once per frame rather than once per ray, since a world hands back the same structure every time. */
    private EmptySpace frameEmpty = EmptySpace.NONE;

    private int skyHere;
    private boolean skyKnown;
    private boolean fog;
    private double fogStart;
    private double fogEnd;

    /** What the frame fades into instead of the sky, and 0 for a camera that is not under water. */
    private int submerged;

    /** The three tables this caster may read, built from the lift it was given. */
    private final Lights tables;

    /** The one this frame reads, which depends on the dimension and so is picked per frame. */
    private float[] lights;

    public RayCaster(Textures atlas) {
        this(atlas, Canopy.DEFAULT);
    }

    public RayCaster(Textures atlas, Canopy canopy) {
        this(atlas, canopy, SHADOW_LIFT, true);
    }

    /** @param shadowLift how far off black an unlit block is drawn - see {@link #SHADOW_LIFT} */
    public RayCaster(Textures atlas, Canopy canopy, float shadowLift) {
        this(atlas, canopy, shadowLift, true);
    }

    RayCaster(Textures atlas, boolean skipEmpty) {
        this(atlas, Canopy.DEFAULT, SHADOW_LIFT, skipEmpty);
    }

    RayCaster(Textures atlas, Canopy canopy, boolean skipEmpty) {
        this(atlas, canopy, SHADOW_LIFT, skipEmpty);
    }

    RayCaster(Textures atlas, Canopy canopy, float shadowLift, boolean skipEmpty) {
        this(atlas, canopy, shadowLift, skipEmpty, true);
    }

    RayCaster(Textures atlas, Canopy canopy, float shadowLift, boolean skipEmpty, boolean skipSameKind) {
        this.atlas = atlas;
        this.canopy = canopy;
        this.skipEmpty = skipEmpty;
        this.skipSameKind = skipSameKind;
        this.entityTracer = new EntityTracer(atlas);
        this.tables = Lights.lifted(shadowLift);
        this.lights = tables.overworld();
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
     * One horizontal band of a frame, so several casters can share the work. Bands rather than tiles because a row is
     * contiguous in {@code out}, so two threads never write the same cache line, and everything a band reads is
     * immutable or its own.
     *
     * @param fromRow inclusive, {@code toRow} exclusive
     */
    public void render(VoxelSource world, CameraView view, List<EntitySnapshot> entities,
                       int width, int height, int[] out, int fromRow, int toRow) {

        render(world, view, entities, null, width, height, out, fromRow, toRow);
    }

    /**
     * The same, with the entities' screen rects already worked out.
     *
     * <p>For {@link FrameTracer}, which cuts a frame into several bands per thread and so would otherwise build the same
     * {@link EntityScreen} - over every row of the frame, not just this band's - once per band. Immutable once built, so
     * every band reads the one copy.
     *
     * @param prepared the screen for this whole frame, or null to work it out here
     */
    void render(VoxelSource world, CameraView view, List<EntitySnapshot> entities, EntityScreen prepared,
                int width, int height, int[] out, int fromRow, int toRow) {

        // Everything below reads the world through the memo rather than directly - see SeenBlocks. The two are the same
        // world; this one only remembers what it has already been told.
        world = seen.over(world);

        fog = view.fog();
        // Only the last stretch fades, which is what turns the distance cap into a haze instead of a wall.
        fogStart = view.maxDistance() - taperOf(view.maxDistance());
        fogEnd = view.maxDistance();

        // The Nether's air hides distance on its own. Always on rather than optional: terrain drawn sharp to the
        // horizon there does not read as the Nether at all.
        if (world.sky().foggyAir()) {
            fog = true;
            fogStart = view.maxDistance() * FOGGY_AIR_START;
            fogEnd = Math.min(view.maxDistance(), FOGGY_AIR_CAP) * FOGGY_AIR_END;
        }

        // Water wins over the air's fog, being the nearer medium, and it starts before the camera.
        lights = tables.forAmbient(world.sky().ambientLight());
        submerged = world.submergedIn();
        if (submerged != 0) {
            fog = true;
            fogStart = WATER_FOG_START;
            fogEnd = Math.min(view.maxDistance(), world.submergedSight());
        }

        frameView = view;
        // For a clipped frame the walk does not start where the camera is, so its first block is not the camera's own -
        // see crossedInto. Null for every other capture, which is what keeps them stepping exactly as they did.
        crossedInto = view.clip() == null ? null : crossedInto(view.clip());
        frameEmpty = skipEmpty ? world.emptySpace() : EmptySpace.NONE;
        // Emptied per frame rather than trusted across them: the same caster renders the next snapshot too, and the
        // water in it has moved. A thousand longs is nothing next to a frame.
        Arrays.fill(fluidKeys, NO_POSITION);
        // And the biome under a camera that has been carried somewhere else since.
        Arrays.fill(tintKeys, NO_POSITION);
        CameraView.Frame frame = view.frame();
        EntityScreen screen = prepared != null ? prepared
                : entities.isEmpty() ? null : new EntityScreen(entities, view, width, height);
        // Projection stays measured from the apex whatever a clip does, so the screen rects above and the pixel
        // directions below agree; only where a ray starts walking moves.
        CameraView.ClipPlane clip = view.clip();

        for (int py = fromRow; py < toRow; py++) {
            int[] row = screen == null ? null : screen.row(py);

            for (int px = 0; px < width; px++) {
                // A pixel the caller has said it will not look at is the cheapest kind of ray: the one not cast. Left
                // as it was found, which the caller is promised is transparent - see CameraEye.Mask.
                if (!view.wants(px, py, width)) {
                    continue;
                }

                frame.direction(px, py, width, height, direction);

                // Left uncomputed until something wants it: a sky is a gradient, a glow, a star hash, two celestial
                // discs and a cloud sheet, and a pixel behind an opaque near surface never asks.
                skyKnown = false;

                // Where this ray enters the half-space it is allowed to see, which for an unclipped view - every
                // capture out of a player's own head - is the eye itself and costs nothing to work out.
                double enter = clip == null ? 0
                        : clip.entry(view.x(), view.y(), view.z(), direction[0], direction[1], direction[2]);

                fragments.reset();

                // A ray that never crosses the plane sees nothing at all. Skipping it rather than walking it is not
                // only cheaper: the walk would start at infinity and read blocks at coordinates that are not numbers.
                if (Double.isFinite(enter)) {
                    double originX = view.x() + direction[0] * enter;
                    double originY = view.y() + direction[1] * enter;
                    double originZ = view.z() + direction[2] * enter;

                    traceBlocks(world, view, originX, originY, originZ, direction[0], direction[1], direction[2]);

                    if (row != null) {
                        traceEntities(world, screen, row, px, py, view, originX, originY, originZ,
                                direction[0], direction[1], direction[2]);
                    }
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
                               double originX, double originY, double originZ, double dx, double dy, double dz) {

        double limit = Math.min(fragments.opaqueDistance(), view.maxDistance());

        for (int index : row) {
            if (!screen.covers(index, px, py)) {
                continue;
            }

            // Every surface of this entity the ray meets, nearest first, and not just the first of them. A slime is
            // one mesh holding both its shells, so stopping at the nearest texel draws the outer one over an inner
            // one that is never looked for. Each pass starts where the last ended and the walk is bounded by the
            // fragment list, so an entity with nothing to see into costs the one pass it always cost.
            EntitySnapshot drawn = screen.entity(index);
            boolean more = entityTracer.first(drawn, originX, originY, originZ, dx, dy, dz, limit);
            while (more) {
                double at = entityTracer.distance();
                int lit = drawn.lit()
                        ? litEntity(world, entityTracer.color(), entityTracer.face(),
                                originX + dx * at, originY + dy * at, originZ + dz * at)
                        : entityTracer.color() & 0xFFFFFF;
                if (fog && at > fogStart) {
                    lit = fogged(lit, at, backdrop(world));
                }

                // Carried at the texture's own alpha rather than forced solid. A slime's outer shell is 180 of 255
                // in the texture itself, which is what the client blends it by, and rounding that up to solid hid
                // its inner cube, every other inner cube, and anything a sulfur cube had been given to hold.
                int alpha = entityTracer.color() >>> 24;
                if (!fragments.add(alpha << 24 | lit & 0xFFFFFF, (float) at)) {
                    break;
                }

                // Only a solid texel closes the ray off. Shortening the limit on a see-through one is what stopped
                // whatever stood behind it from ever being looked for.
                if (alpha == 0xFF) {
                    limit = Math.min(limit, at);
                    break;
                }
                more = entityTracer.next(limit);
            }
        }
    }

    /**
     * An entity texel shaded by where it is standing - drawn at its texture's own brightness a mob is lit for noon
     * wherever it is, and a cave full of fully lit zombies reads as pasted on. The light is read at the hit point,
     * which is the air the model occupies rather than the block underneath it.
     *
     * <p>Skipped for anything that is <b>not lit</b> - see {@link EntitySnapshot#emissive}, which is a picture rather
     * than matter and carries its own light in its pixels.
     */
    private int litEntity(VoxelSource world, int texel, Direction face, double atX, double atY, double atZ) {
        int light = world.lightAt((int) Math.floor(atX), (int) Math.floor(atY), (int) Math.floor(atZ));
        float factor = FACE_SHADE[face.ordinal()] * litBy(light);

        int red = Math.round((texel >> 16 & 0xFF) * factor);
        int green = Math.round((texel >> 8 & 0xFF) * factor);
        int blue = Math.round((texel & 0xFF) * factor);
        return red << 16 | green << 8 | blue;
    }

    /**
     * One ray through the blocks, adding whatever it passes to {@link #fragments}.
     *
     * <p>The origin is handed in rather than read off the view, because a clipped frame starts each ray where it
     * crosses the plane instead of at the eye - so distances here are measured from there.
     */
    private void traceBlocks(VoxelSource world, CameraView view, double originX, double originY, double originZ,
                             double dx, double dy, double dz) {

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
        // not paint that block's inside over the whole frame. A clipped frame is the exception, and the reason is that
        // its walk starts at the plane rather than at the camera - see crossedInto.
        Direction entered = crossedInto;
        // What the ray is coming out of, or null where it has just crossed something it did not examine - see
        // hiddenByItsOwnKind. Cleared by every jump, since the block a jump lands on has empty space behind it.
        BakedState behind = null;
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

        EmptySpace empty = frameEmpty;

        // Last cell asked about, so a ray inside an occupied cell asks once for it rather than once per block.
        int askedX = Integer.MIN_VALUE;
        int askedY = Integer.MIN_VALUE;
        int askedZ = Integer.MIN_VALUE;

        while (travelled <= range) {
            if (entered != null) {
                if (blockY > ceiling && dy >= 0) break;
                if (blockY < floor && dy <= 0) break;

                int cellX = blockX >> EmptySpace.CELL;
                int cellY = blockY >> EmptySpace.CELL;
                int cellZ = blockZ >> EmptySpace.CELL;
                if (cellX != askedX || cellY != askedY || cellZ != askedZ) {
                    askedX = cellX;
                    askedY = cellY;
                    askedZ = cellZ;

                    // Empty space is crossed in one go rather than a block at a time. Walking it was most of what a
                    // frame did - pointed at the sky, six steps in seven were inside a cube already known to hold
                    // nothing - and the cube's far side can be solved for outright from the boundary distances the
                    // walk is already carrying. Nothing is sampled in between either way, so the only thing lost is
                    // the stepping.
                    int shift = empty.shiftAt(blockX, blockY, blockZ);
                    if (shift != 0) {
                        int size = 1 << shift;
                        int leaveX = (blockX & -size) + (stepX > 0 ? size : -1);
                        int leaveY = (blockY & -size) + (stepY > 0 ? size : -1);
                        int leaveZ = (blockZ & -size) + (stepZ > 0 ? size : -1);

                        // Where the ray meets the far side on each axis: nextAxis is where it enters the neighbouring
                        // block, and every block after that is one delta further along.
                        double exitX = nextX + ((leaveX - blockX) * stepX - 1) * deltaX;
                        double exitY = nextY + ((leaveY - blockY) * stepY - 1) * deltaY;
                        double exitZ = nextZ + ((leaveZ - blockZ) * stepZ - 1) * deltaZ;

                        // Whichever comes first, preferred on a tie exactly as the step below prefers them, so a ray
                        // through a corner leaves by the same face either way. The axis that won lands on the block
                        // its own far side was worked out from, and the other two wherever the ray has got to.
                        double exit;
                        if (exitX < exitY && exitX < exitZ) {
                            exit = exitX;
                            entered = stepX > 0 ? Direction.WEST : Direction.EAST;
                            blockX = leaveX;
                            blockY = blockAt(originY, dy, stepY, exit);
                            blockZ = blockAt(originZ, dz, stepZ, exit);
                        } else if (exitY < exitZ) {
                            exit = exitY;
                            entered = stepY > 0 ? Direction.DOWN : Direction.UP;
                            blockX = blockAt(originX, dx, stepX, exit);
                            blockY = leaveY;
                            blockZ = blockAt(originZ, dz, stepZ, exit);
                        } else {
                            exit = exitZ;
                            entered = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                            blockX = blockAt(originX, dx, stepX, exit);
                            blockY = blockAt(originY, dy, stepY, exit);
                            blockZ = leaveZ;
                        }

                        nextX = boundary(originX, blockX, dx, deltaX);
                        nextY = boundary(originY, blockY, dy, deltaY);
                        nextZ = boundary(originZ, blockZ, dz, deltaZ);
                        travelled = exit;
                        behind = null;
                        continue;
                    }
                }

                if (blockX != columnX || blockZ != columnZ) {
                    columnX = blockX;
                    columnZ = blockZ;
                    columnTop = world.columnTop(blockX, blockZ);
                }

                // Over open ground, across a whole patch of columns rather than one at a time. What is left after
                // empty space has been skipped is nearly all of this: a frame at the horizon spends nine steps in
                // ten above the terrain, in the band of air inside the sixteen block cell the ground surface sits
                // in - too low for the cell to be called empty and too high to hold anything.
                if (blockY > columnTop) {
                    int patch = patchAbove(world, blockX, blockY, blockZ);
                    if (patch > 0) {
                        int size = 1 << patch;
                        int leaveX = (blockX & -size) + (stepX > 0 ? size : -1);
                        int leaveZ = (blockZ & -size) + (stepZ > 0 ? size : -1);

                        double exitX = nextX + ((leaveX - blockX) * stepX - 1) * deltaX;
                        double exitZ = nextZ + ((leaveZ - blockZ) * stepZ - 1) * deltaZ;
                        // Downward the air ends at the tallest thing in the patch. Level or climbing it does not
                        // end at all, and the loop's own ceiling test is what stops those.
                        int patchTop = world.maxTopIn(blockX, blockZ, patch);
                        double exitY = stepY > 0
                                ? Double.MAX_VALUE
                                : nextY + (blockY - patchTop - 1) * deltaY;

                        double exit;
                        if (exitX < exitY && exitX < exitZ) {
                            exit = exitX;
                            entered = stepX > 0 ? Direction.WEST : Direction.EAST;
                            blockX = leaveX;
                            blockY = blockAt(originY, dy, stepY, exit);
                            blockZ = blockAt(originZ, dz, stepZ, exit);
                        } else if (exitY < exitZ) {
                            exit = exitY;
                            entered = Direction.UP;
                            blockX = blockAt(originX, dx, stepX, exit);
                            blockY = patchTop;
                            blockZ = blockAt(originZ, dz, stepZ, exit);
                        } else {
                            exit = exitZ;
                            entered = stepZ > 0 ? Direction.NORTH : Direction.SOUTH;
                            blockX = blockAt(originX, dx, stepX, exit);
                            blockY = blockAt(originY, dy, stepY, exit);
                            blockZ = leaveZ;
                        }

                        nextX = boundary(originX, blockX, dx, deltaX);
                        nextY = boundary(originY, blockY, dy, deltaY);
                        nextZ = boundary(originZ, blockZ, dz, deltaZ);
                        travelled = exit;
                        behind = null;
                        continue;
                    }
                }

                // Above everything in this column there is nothing to ask about, and asking is a chunk lookup and
                // a block read where stepping on is a few adds.
                if (blockY <= columnTop && blockY >= floor && blockY <= roof) {
                    BakedState state = world.stateAt(blockX, blockY, blockZ);
                    if (state.isEmpty()) {
                        // Air behind the next block, so its face on this side is drawn rather than culled.
                        behind = null;
                    } else {
                        // The inside of a body of water, of a pane of glass, of anything one material thick enough to
                        // walk through: the same state on the side we came in from hides whatever this block would draw.
                        if (!skipSameKind || state != behind || !hiddenByItsOwnKind(state, entered)) {
                            if (!sample(world, state, entered, blockX, blockY, blockZ, originX, originY, originZ, dx, dy, dz, travelled)) {
                                break;
                            }
                        }
                        behind = state;
                    }
                } else {
                    behind = null;
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

    /**
     * How wide a patch of columns this position is clear of, as a shift, or 0 for none worth jumping.
     *
     * <p>Climbed rather than asked for outright, because the answer wanted is the largest patch the ray is still
     * above and only the world can say where each one's tallest block is.
     */
    private static int patchAbove(VoxelSource world, int blockX, int blockY, int blockZ) {
        int patch = 0;
        while (patch < WIDEST_PATCH && world.maxTopIn(blockX, blockZ, patch + 1) < blockY) patch++;
        return patch;
    }

    /**
     * Widest patch of columns a ray will cross in one go, as a shift: eight columns.
     *
     * <p>Where it stops paying. Sixteen took the step count from 13.1 to 12.5 on a frame over hills and asked
     * about a tenth more columns to do it, and a patch is only as tall as the tallest thing in it - so widening
     * one mostly buys a lower ceiling to fly under.
     */
    private static final int WIDEST_PATCH = 3;

    /**
     * Which block one axis is in at a distance along the ray, for the two axes a jump did not leave by.
     *
     * <p>Read off the position rather than counted in boundaries. Counting meant dividing the distance by the
     * spacing and then correcting the answer, because a division of doubles is a guess at an integer - three
     * divisions and their correction loops per jump, which measured at a fifth of a frame and gave most of the
     * jumping's saving straight back. A position is a multiply.
     *
     * <p>The axis a jump <i>did</i> leave by is not read off here at all: its block is the one its own far side was
     * worked out from, and a position that should land exactly on that boundary can come out a hair under it.
     */
    private static int blockAt(double origin, double direction, int step, double at) {
        // An axis the ray does not move along is where it started, and asking is what would go wrong: the boundary
        // case below reads an eye at a whole number of blocks as having just crossed one.
        if (direction == 0) return (int) Math.floor(origin);

        double position = origin + direction * at;
        int block = (int) Math.floor(position);
        // Exactly on a boundary heading down, the ray is entering the block under it, which a floor cannot see. Not
        // a rounding nicety: left on the block above, that axis' next boundary comes out as the distance already
        // travelled, the jump is taken again from the same place, and the ray stops going anywhere at all.
        return step < 0 && position == block ? block - 1 : block;
    }

    /** Distance along the ray to the first block boundary on one axis. */
    private static double boundary(double origin, int block, double direction, double delta) {
        if (direction == 0) return Double.MAX_VALUE;

        double fraction = direction > 0 ? block + 1 - origin : origin - block;
        return fraction * delta;
    }

    /**
     * Whether an identical block on the side the ray came in through hides everything this one would draw.
     *
     * <p>What a body of water is made of. Water is translucent, so a ray does not stop at the surface - it walks down
     * through every block of the ocean to the seabed - and <b>every one of those blocks draws nothing</b>, because the
     * face it would draw is culled against the identical water behind it. Eighteen blocks of standing water measured at
     * <b>164 ms</b> against 16 ms for the same scene in stone, and all of the difference was the renderer asking, block
     * after block, a question it had already answered.
     *
     * <p>The test is the one {@link #culled} would make, decided from the state alone: a single full cube, not opaque, and
     * a face on this side that carries a {@code cullface}. Given all three, an identical neighbour culls it - by the fluid
     * rule where it holds fluid and by the identity rule otherwise - so there is nothing to add and nothing to stop the
     * ray. Only the <b>entered</b> face matters, since a full cube is reported by the grid walk and the walk only ever
     * consults the face it came in through.
     *
     * <p>Not a jump: the ray still steps a block at a time, because only a block read can say where the water ends. What
     * it saves is everything except that read - the element walk, the face lookup, and the neighbour read inside
     * {@link #culled} - and that was nearly all of it.
     *
     * <p>Remembered per state and side rather than worked out per block, keyed on identity because states are
     * canonicalised: one instance per distinct state, so the answer for a given pair never changes. Two six-slot arrays,
     * which is all a run of one material needs, and a stale entry from an earlier frame is still the right answer.
     */
    private boolean hiddenByItsOwnKind(BakedState state, Direction entered) {
        int side = entered.ordinal();
        if (hides[side] == state) return true;
        if (showsThrough[side] == state) return false;

        boolean hidden = decideHiddenByItsOwnKind(state, entered);
        (hidden ? hides : showsThrough)[side] = state;
        return hidden;
    }

    private static boolean decideHiddenByItsOwnKind(BakedState state, Direction entered) {
        // Opaque never gets here twice: the first one ends the ray. Two elements mean the walk consults more than the
        // face it came in through, and anything but a full cube has geometry the grid walk has not tested.
        if (state.alpha() == BakedState.Alpha.OPAQUE || state.elements().size() != 1) return false;

        BakedElement only = state.elements().getFirst();
        if (!only.isFullBlock()) return false;

        BakedFace drawn = only.face(entered);
        // No face at all draws nothing either way. A face without a cullface is drawn whatever is behind it.
        return drawn == null || drawn.cull() != null;
    }

    /** See {@link #hiddenByItsOwnKind}: the states known to hide, and to show, through each of the six sides. */
    private final BakedState[] hides = new BakedState[6];
    private final BakedState[] showsThrough = new BakedState[6];

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
                int corners = tilt(world, state, element, blockX, blockY, blockZ);
                hit = corners == LEVEL
                        ? enterBox(element, blockX, blockY, blockZ, originX, originY, originZ, dx, dy, dz)
                        : enterFluid(corners, blockX, blockY, blockZ, originX, originY, originZ, dx, dy, dz);
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

            // Only a fluid's own top runs anywhere. Its sides are the still texture in the client too.
            float running = drawn.fluid() && face == Direction.UP && state.fluidFlow() != null
                    ? flow(world, state, blockX, blockY, blockZ)
                    : FluidSurface.STILL;

            int texel = Float.isNaN(running)
                    ? texel(element, drawn, face, localX, localY, localZ)
                    : flowing(state, running, localX, localZ);
            int alpha = state.alpha() == BakedState.Alpha.OPAQUE ? 255 : texel >>> 24;
            if (alpha == 0) {
                // A gap in a distant canopy is smaller than the pixel looking through it, so what is behind it gets a
                // share of that pixel rather than one of its own. Filling the gap with the leaf color is that share.
                float fill = state.leaves() ? canopy.fill(hit) : 0;
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
     * <p>A fluid needs its own rule rather than the identity one, since a source, a flowing block and a waterlogged
     * stair are three states holding the same water - comparing states leaves seams at the edges of a pool.
     *
     * <p>And a fourth case that is not about the neighbour at all: for a <b>clipped</b> frame, a neighbour behind the plane
     * is not in the picture and cannot hide anything in it.
     */
    private boolean culled(VoxelSource world, BakedState state, BakedFace drawn,
                           int blockX, int blockY, int blockZ) {
        Direction against = drawn.cull();
        if (against == null) return false;

        int nextX = blockX + against.dx();
        int nextY = blockY + against.dy();
        int nextZ = blockZ + against.dz();

        // A neighbour on the far side of a clip plane is not in this picture, so it hides nothing in it. Which is the
        // difference between a mirror showing the chest pushed against it and showing a hole where the chest is: the face
        // that touches the glass is hidden by the wall the mirror hangs on, and that wall is exactly what a reflection
        // does not draw. Only for a clipped frame, so an ordinary capture culls what it always culled.
        CameraView.ClipPlane clip = frameView == null ? null : frameView.clip();
        if (clip != null && !clip.keeps(nextX + 0.5, nextY + 0.5, nextZ + 0.5)) return false;

        BakedState neighbour = world.stateAt(nextX, nextY, nextZ);
        if (neighbour.isEmpty()) return false;

        if (neighbour.fullCube() && neighbour.alpha() == BakedState.Alpha.OPAQUE) return true;

        // The whole face, however much deeper the neighbour's fluid is, which is what the client does. It can,
        // because both blocks average the corners they share and their tops meet along the edge between them -
        // so there is no step between two depths to leave a gap. Drawing part of the side instead was patching a
        // hole that a sloped surface does not have.
        if (drawn.fluid() && neighbour.fluidTop() > 0 && neighbour.water() == state.water()) {
            return true;
        }

        // Identity is enough: states are cached per state string, so two panes of the same glass are one object.
        return neighbour == state && state.alpha() != BakedState.Alpha.OPAQUE;
    }

    /** A top that is flat, which the ordinary box test already draws exactly. */
    private static final int LEVEL = 0;

    /**
     * The corner heights of a fluid's surface, or {@link #LEVEL} for an element that is not one.
     *
     * <p>Asked for every fluid surface rather than only a visibly tilted one, because the corners are the height
     * even when all four agree: a lone source stands at eight ninths but averages down to three quarters against
     * the air around it, and drawing it at the height its own state carries makes every puddle too deep.
     *
     * <p>The body under the surface is untouched. Fluid with more of the same above it is full to the brim, which
     * is a full block and never reaches here, so an ocean is flat boxes and only its top is ever solved for.
     */
    private int tilt(VoxelSource world, BakedState state, BakedElement element, int blockX, int blockY, int blockZ) {
        BakedFace top = element.face(Direction.UP);
        if (top == null || !top.fluid()) return LEVEL;

        return fluidCorners[remember(world, state, blockX, blockY, blockZ)];
    }

    /** Which way the fluid at a position runs, off the same remembered entry its corners came from. */
    private float flow(VoxelSource world, BakedState state, int x, int y, int z) {
        return fluidFlows[remember(world, state, x, y, z)];
    }

    /**
     * The slot holding what was worked out about the fluid at a position, filling it first if it holds something
     * else. Direct-mapped so a miss costs a compare, and read once per position rather than once per ray - which is
     * the point, since what it holds takes eight neighbours to arrive at.
     *
     * <p>Good for the frame it was filled in because the world a frame traces cannot change under it: a snapshot is
     * taken in one tick and then only read.
     */
    private int remember(VoxelSource world, BakedState state, int x, int y, int z) {
        long key = (long) (x & 0x1FFFFF) << 42 | (long) (y & 0xFFFFF) << 22 | z & 0x3FFFFF;
        int slot = (int) (key ^ key >>> 32) & FLUID_SLOTS - 1;
        if (fluidKeys[slot] == key) return slot;

        // Both at once, because the two read the same neighbours and a surface that is drawn needs each of them.
        fluidCorners[slot] = FluidSurface.corners(world, state, x, y, z);
        fluidFlows[slot] = FluidSurface.flow(world, state, x, y, z);
        fluidKeys[slot] = key;
        return slot;
    }

    /**
     * Where the ray enters a fluid whose surface is tilted, or NaN if it misses.
     *
     * <p>The fluid is the space under a bilinear sheet through its four corner heights rather than a box, so the
     * top is solved for instead of being one more slab. Bilinear and not a plane through the same four points:
     * along any edge the sheet is the straight line between the two corners on it, which the neighbouring block
     * draws as its own edge too, and that exact agreement is the whole reason the face between them can be dropped.
     * A plane fitted to four corners that do not lie in one would leave a crack down every shared edge.
     *
     * <p>Substituting the ray into the sheet gives a quadratic, and it is only genuinely one where the four corners
     * are a saddle - a stream that simply tilts one way solves as a line.
     */
    private double enterFluid(int corners, int blockX, int blockY, int blockZ,
                              double originX, double originY, double originZ,
                              double dx, double dy, double dz) {

        double northWest = FluidSurface.northWest(corners);
        double northEast = FluidSurface.northEast(corners);
        double southEast = FluidSurface.southEast(corners);
        double southWest = FluidSurface.southWest(corners);

        double ox = originX - blockX;
        double oy = originY - blockY;
        double oz = originZ - blockZ;

        // The sheet, as height over the block's own corner: north-west is the origin, x runs east and z runs south.
        double base = northWest;
        double alongX = northEast - northWest;
        double alongZ = southWest - northWest;
        double twist = northWest - northEast - southWest + southEast;

        double tallest = Math.max(Math.max(northWest, northEast), Math.max(southEast, southWest));
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int enterAxis = -1;
        boolean enterFromLow = true;

        for (int axis = 0; axis < 3; axis++) {
            double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            double direction = axis == 0 ? dx : axis == 1 ? dy : dz;
            double high = axis == 1 ? tallest : 1;

            if (Math.abs(direction) < 1e-12) {
                if (origin < 0 || origin > high) return Double.NaN;
                continue;
            }

            double inverse = 1 / direction;
            double first = -origin * inverse;
            double second = (high - origin) * inverse;
            double near = Math.min(first, second);
            if (near > enter) {
                enter = near;
                enterAxis = axis;
                enterFromLow = direction > 0;
            }
            exit = Math.min(exit, Math.max(first, second));
        }

        if (exit < enter || exit < 0 || enterAxis < 0) return Double.NaN;

        double start = Math.max(enter, 0);
        double at = start;
        Direction face = sideOf(enterAxis, enterFromLow);

        // Where the ray comes in over the surface it has not reached the fluid yet - a side face is only fluid up to
        // the sheet, and above that the ray carries on to meet the top from outside. That is the same test that
        // makes a stream's step look like tilted water rather than a wall.
        if (above(base, alongX, alongZ, twist, ox + dx * start, oy + dy * start, oz + dz * start)) {
            at = crossing(
                    -twist * dx * dz,
                    dy - alongX * dx - alongZ * dz - twist * (ox * dz + oz * dx),
                    oy - base - alongX * ox - alongZ * oz - twist * ox * oz,
                    start, exit
            );
            if (Double.isNaN(at)) return Double.NaN;

            face = Direction.UP;
        }

        boxFace = face;
        boxHitX = (ox + dx * at) * 16;
        boxHitY = (oy + dy * at) * 16;
        boxHitZ = (oz + dz * at) * 16;
        return at;
    }

    /** Whether a point is over the sheet rather than in the fluid under it. */
    private static boolean above(double base, double alongX, double alongZ, double twist, double x, double y, double z) {
        return y > base + alongX * x + alongZ * z + twist * x * z;
    }

    /** The first crossing of the sheet in range, of a quadratic that is a line whenever the corners are not a saddle. */
    private static double crossing(double square, double linear, double constant, double start, double exit) {
        if (Math.abs(square) < 1e-12) {
            if (Math.abs(linear) < 1e-12) return Double.NaN;

            double at = -constant / linear;
            return at >= start && at <= exit ? at : Double.NaN;
        }

        double discriminant = linear * linear - 4 * square * constant;
        if (discriminant < 0) return Double.NaN;

        double root = Math.sqrt(discriminant);
        double first = (-linear - root) / (2 * square);
        double second = (-linear + root) / (2 * square);
        if (first > second) {
            double swap = first;
            first = second;
            second = swap;
        }

        if (first >= start && first <= exit) return first;

        return second >= start && second <= exit ? second : Double.NaN;
    }

    private static Direction sideOf(int axis, boolean fromLow) {
        return switch (axis) {
            case 0 -> fromLow ? Direction.WEST : Direction.EAST;
            case 1 -> fromLow ? Direction.DOWN : Direction.UP;
            default -> fromLow ? Direction.NORTH : Direction.SOUTH;
        };
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

        double across = BakedFace.u(modelFace, mx, my, mz);
        double down = BakedFace.v(modelFace, mx, my, mz);

        // A quarter turn takes the texture's across from the face's down, and the rect was fitted to that span when
        // it was baked - so the swap is the whole of what is left of the rotation here.
        double u = drawn.swapsAxes() ? down : across;
        double v = drawn.swapsAxes() ? across : down;

        // Into the rect the model states for this face, which is how a slab takes the bottom half of a texture.
        float su = (float) (drawn.u1() + u / 16 * (drawn.u2() - drawn.u1()));
        float sv = (float) (drawn.v1() + v / 16 * (drawn.v2() - drawn.v1()));
        return atlas.get(drawn.texture()).sample(su, sv);
    }

    /**
     * A moving fluid's surface, drawn with the flowing texture turned to face downhill.
     *
     * <p>The client's own mapping: the face takes a half-size window of the sprite, centred and turned by the flow
     * angle, so the lines in the texture run the way the water does. Half-size is what keeps the window inside the
     * sprite at every angle - turned about its middle, a square of that size never reaches an edge.
     */
    private int flowing(BakedState state, float angle, double localX, double localZ) {
        double across = Math.cos(angle) * 0.25;
        double down = Math.sin(angle) * 0.25;

        // The face in -1 to 1 about its middle, which is the space the window is stated in.
        double east = localX / 8 - 1;
        double south = localZ / 8 - 1;

        float u = (float) (0.5 + across * east + down * south);
        float v = (float) (0.5 + across * south - down * east);
        return atlas.get(state.fluidFlow()).sample(u * 16, v * 16);
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
            int tint = fixed != 0 ? fixed : tintOf(world, blockX, blockY, blockZ, drawn.tint());
            red = red * (tint >> 16 & 0xFF) / 255;
            green = green * (tint >> 8 & 0xFF) / 255;
            blue = blue * (tint & 0xFF) / 255;
        }

        return (texel & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    /**
     * A position's tint, remembered per position the way {@link #remember} remembers a fluid's corners, and good for
     * the same reason: the world a frame traces is a snapshot and cannot change under it.
     *
     * <p>The index is part of the key rather than a table per index. A face carries one of four the world answers -
     * grass, foliage, dry foliage, water - and a block can show two of them at once, a lily pad over a pond being
     * the everyday case.
     */
    private int tintOf(VoxelSource world, int x, int y, int z, int index) {
        // The index takes the low five bits, which is exactly the range that gets here: anything from Tints.FIRST_FIXED
        // up was answered before the call.
        long key = (long) (x & 0xFFFFF) << 41 | (long) (y & 0xFFFF) << 25 | (long) (z & 0xFFFFF) << 5 | index & 0x1F;
        int slot = (int) (key ^ key >>> 32) & TINT_SLOTS - 1;
        if (tintKeys[slot] == key) return tintValues[slot];

        int tint = world.tintAt(x, y, z, index);
        tintValues[slot] = tint;
        tintKeys[slot] = key;
        return tint;
    }
}
