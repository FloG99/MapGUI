package de.flog99.mapgui.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A world made of whatever a test puts in it, with no server and no assets involved. */
final class TestWorld implements VoxelSource, Textures {

    static final int SKY = 0xFF80A0FF;

    /** Flat, because a test wants a background it can compare against rather than a gradient. */
    private static final Sky PLAIN_SKY = Sky.flat(SKY);

    private final Map<Long, BakedState> blocks = new HashMap<>();
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<Long, Integer> lights = new HashMap<>();
    private final Map<Integer, Integer> tints = new HashMap<>();

    private int defaultLight = 15;

    /** Extent of whatever has been placed, which is the box the empty-space structure is built over. */
    private int fromX = Integer.MAX_VALUE;
    private int fromZ = Integer.MAX_VALUE;
    private int toX = Integer.MIN_VALUE;
    private int toZ = Integer.MIN_VALUE;

    /** Volatile because a frame is traced on several threads and each band asks for this, so it must publish safely. */
    private volatile EmptySpace empty;

    /** How many times a trace has asked about the world, so a test can tell a skip happened rather than assume it. */
    private int reads;

    /** A 16x16 texture of one color, opaque unless an alpha is given. */
    static Texture solid(int argb) {
        int[] pixels = new int[256];
        java.util.Arrays.fill(pixels, argb);
        BakedState.Alpha alpha = switch (argb >>> 24) {
            case 255 -> BakedState.Alpha.OPAQUE;
            case 0 -> BakedState.Alpha.CUTOUT;
            default -> BakedState.Alpha.TRANSLUCENT;
        };
        return new Texture(16, 16, pixels, alpha, argb | 0xFF000000);
    }

    /** Half transparent and half opaque across x, for testing that a ray passes through the clear half. */
    static Texture halfClear(int argb) {
        int[] pixels = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = x < 8 ? 0 : argb;
            }
        }
        return new Texture(16, 16, pixels, BakedState.Alpha.CUTOUT, argb);
    }

    /**
     * Four quadrants in four colours, so which way round a face is drawn is readable from any one pixel of it.
     *
     * <p>Named as the texture is laid out - {@code v} downward from the top - since that is the only frame of
     * reference a texture has of its own.
     */
    static Texture quadrants(int topLeft, int topRight, int bottomLeft, int bottomRight) {
        int[] pixels = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = y < 8 ? (x < 8 ? topLeft : topRight) : (x < 8 ? bottomLeft : bottomRight);
            }
        }
        return new Texture(16, 16, pixels, BakedState.Alpha.OPAQUE, topLeft);
    }

    /**
     * A whole cube carrying a blockstate {@code y} rotation, the way a bed or a door is baked.
     *
     * <p>A cube's shape survives the turn, so the geometry is stated unrotated - which is exactly what the bake
     * produces for one, and what leaves the rotation visible only in the texture.
     */
    TestWorld turnedCube(int x, int y, int z, String texture, int rotY) {
        BakedFace[] faces = new BakedFace[6];
        for (Direction side : Direction.values()) {
            faces[side.rotate(0, rotY).ordinal()] = BakedFace.whole(texture);
        }
        return place(x, y, z, new BakedState(List.of(new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, rotY)), false, BakedState.Alpha.OPAQUE));
    }

    TestWorld texture(String name, Texture texture) {
        textures.put(name, texture);
        return this;
    }

    /** A full cube of one texture on every face. */
    TestWorld cube(int x, int y, int z, String texture, BakedState.Alpha alpha) {
        return cube(x, y, z, texture, alpha, BakedFace.NO_TINT);
    }

    TestWorld cube(int x, int y, int z, String texture, BakedState.Alpha alpha, int tint) {
        return place(x, y, z, shared("cube " + texture + " " + alpha + " " + tint, () -> {
            BakedFace[] faces = new BakedFace[6];
            for (Direction side : Direction.values()) {
                faces[side.ordinal()] = new BakedFace(texture, 0, 0, 16, 16, 0, tint, null);
            }
            return new BakedState(List.of(new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, 0)), true, alpha);
        }));
    }

    /**
     * A full cube whose faces are dropped against a solid neighbour, which is what a real block model states.
     *
     * <p>{@code cube} above leaves the cullface out, so most tests here never exercise it. This one is for the cases where
     * <b>what hides a face</b> is the question - a clipped frame, where the neighbour that would hide it is not in the
     * picture at all.
     */
    TestWorld culledCube(int x, int y, int z, String texture) {
        return place(x, y, z, shared("culled cube " + texture, () -> {
            BakedFace[] faces = new BakedFace[6];
            for (Direction side : Direction.values()) {
                faces[side.ordinal()] = new BakedFace(texture, 0, 0, 16, 16, 0, BakedFace.NO_TINT, side);
            }
            return new BakedState(List.of(new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, 0)),
                    true, BakedState.Alpha.OPAQUE);
        }));
    }

    /** A cutout cube flagged as leaves, so the gaps in it close up with distance the way a canopy does. */
    TestWorld leafCube(int x, int y, int z, String texture) {
        return place(x, y, z, shared("leaf " + texture, () -> {
            BakedFace[] faces = new BakedFace[6];
            for (Direction side : Direction.values()) {
                faces[side.ordinal()] = new BakedFace(texture, 0, 0, 16, 16, 0, BakedFace.NO_TINT, null);
            }
            BakedElement cube = new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, 0);
            return new BakedState(List.of(cube), true, BakedState.Alpha.CUTOUT, false, true, 0);
        }));
    }

    /** An arbitrary box, for the cases where a ray has to be able to miss the geometry inside a block. */
    TestWorld box(int x, int y, int z, float fromX, float fromY, float fromZ, float toX, float toY, float toZ, String texture) {
        String key = "box " + texture + " " + fromX + " " + fromY + " " + fromZ + " " + toX + " " + toY + " " + toZ;
        return place(x, y, z, shared(key, () -> {
            BakedFace[] faces = new BakedFace[6];
            for (Direction side : Direction.values()) {
                faces[side.ordinal()] = BakedFace.whole(texture);
            }
            return new BakedState(List.of(new BakedElement(fromX, fromY, fromZ, toX, toY, toZ, faces, true, 0, 0)), false, BakedState.Alpha.OPAQUE);
        }));
    }

    /** A zero-thickness vertical plane with no face shading - what a cross model like grass is made of. */
    TestWorld plane(int x, int y, int z, String texture) {
        return place(x, y, z, shared("plane " + texture, () -> {
            BakedFace[] faces = new BakedFace[6];
            faces[Direction.NORTH.ordinal()] = BakedFace.whole(texture);
            faces[Direction.SOUTH.ordinal()] = BakedFace.whole(texture);
            return new BakedState(List.of(new BakedElement(1, 0, 8, 15, 16, 8, faces, false, 0, 0)), false, BakedState.Alpha.CUTOUT);
        }));
    }

    /**
     * A cross-style plane turned about the vertical axis, as {@code cross.json} authors grass.
     *
     * <p>Rescaled, so the turned plane still spans the block: that is what the flag means in a model and it is
     * what keeps a 45 degree X from shrinking inside its own cube.
     */
    TestWorld turnedPlane(int x, int y, int z, String texture, float angle) {
        BakedFace[] faces = new BakedFace[6];
        faces[Direction.NORTH.ordinal()] = BakedFace.whole(texture);
        faces[Direction.SOUTH.ordinal()] = BakedFace.whole(texture);
        BakedElement element = new BakedElement(0.8f, 0, 8, 15.2f, 16, 8, faces, false, 0, 0,
                new ElementRotation(8, 8, 8, 1, angle, true));
        return place(x, y, z, new BakedState(List.of(element), false, BakedState.Alpha.CUTOUT));
    }

    /**
     * A solid half-block standing in water, in the order a bake produces: geometry first, fluid appended after.
     *
     * <p>The order is the point. The renderer walks a block's elements as listed, and stopping at the first opaque
     * one drops anything in front of it - which for this block is all of its water.
     */
    TestWorld waterlogged(int x, int y, int z, String solid, String water) {
        BakedFace[] slab = new BakedFace[6];
        for (Direction side : Direction.values()) {
            slab[side.ordinal()] = BakedFace.whole(solid);
        }

        BakedFace[] fluid = new BakedFace[6];
        for (Direction side : Direction.values()) {
            fluid[side.ordinal()] = new BakedFace(water, 0, 0, 16, 16, 0, BakedFace.NO_TINT, side, true);
        }

        return place(x, y, z, shared("waterlogged " + solid + " " + water, () -> new BakedState(List.of(
                new BakedElement(0, 0, 0, 16, 8, 16, slab, true, 0, 0),
                new BakedElement(0, 0, 0, 16, 16, 16, fluid, true, 0, 0)
        ), false, BakedState.Alpha.TRANSLUCENT, true)));
    }

    /**
     * Two coincident full cubes, an opaque one and a partly clear one over it, as a grass block is built.
     *
     * <p>The overlay is second in the list, which is where a model puts it and where the bug was: at identical
     * depth the sort has nothing to go on, so the opaque cube underneath won and the overlay never appeared.
     */
    TestWorld decal(int x, int y, int z, String base, String overlay) {
        BakedFace[] under = new BakedFace[6];
        BakedFace[] over = new BakedFace[6];
        for (Direction side : Direction.values()) {
            under[side.ordinal()] = BakedFace.whole(base);
            over[side.ordinal()] = BakedFace.whole(overlay);
        }

        return place(x, y, z, new BakedState(List.of(
                new BakedElement(0, 0, 0, 16, 16, 16, under, true, 0, 0),
                new BakedElement(0, 0, 0, 16, 16, 16, over, true, 0, 0)
        ), true, BakedState.Alpha.CUTOUT));
    }

    /**
     * A zero-thickness horizontal plane on the block's floor, which is how a flowerbed is built.
     *
     * <p>The two sides carry the same texture and shade very differently - full from above, half from below - so this
     * is the shape that shows whether the entered side was worked out or guessed.
     */
    TestWorld floorPlane(int x, int y, int z, String texture) {
        BakedFace[] faces = new BakedFace[6];
        faces[Direction.UP.ordinal()] = BakedFace.whole(texture);
        faces[Direction.DOWN.ordinal()] = BakedFace.whole(texture);
        return place(x, y, z, new BakedState(List.of(new BakedElement(0, 0, 0, 16, 0, 16, faces, true, 0, 0)),
                false, BakedState.Alpha.CUTOUT));
    }

    TestWorld light(int x, int y, int z, int level) {
        lights.put(key(x, y, z), level);
        return this;
    }

    TestWorld defaultLight(int level) {
        this.defaultLight = level;
        return this;
    }

    /** A translucent full cube whose faces cull against their own kind, the way a fluid does. */
    TestWorld fluid(int x, int y, int z, String texture) {
        BakedFace[] faces = new BakedFace[6];
        for (Direction side : Direction.values()) {
            faces[side.ordinal()] = new BakedFace(texture, 0, 0, 16, 16, 0, BakedFace.NO_TINT, side);
        }
        return place(x, y, z, fluidState(faces));
    }

    /** One instance per texture, since culling between the same kind compares baked states by identity. */
    private BakedState fluidState(BakedFace[] faces) {
        return shared("fluid " + faces[0].texture(),
                () -> new BakedState(List.of(new BakedElement(0, 0, 0, 16, 16, 16, faces, true, 0, 0)), true, BakedState.Alpha.TRANSLUCENT));
    }

    /**
     * One instance per distinct state, which is what the real bake produces and what the renderer relies on.
     *
     * <p>{@code BlockModels} caches baked states by the state's own name, so every stone block in a world is the
     * <b>same object</b>. Two things here depend on that and would quietly stop being tested if this handed out a fresh
     * instance per block: {@code culled} drops the face between two blocks of the same kind by comparing them with
     * {@code ==}, so that a wall of glass is one pane rather than stacked layers of blue; and {@code RayCaster} crosses
     * the inside of one material without examining it on the same comparison - see {@code SameKindSkipTest}, which was
     * vacuous until this was fixed, because no two of its blocks were ever the same object.
     */
    private BakedState shared(String key, java.util.function.Supplier<BakedState> build) {
        return states.computeIfAbsent(key, ignored -> build.get());
    }

    private final Map<String, BakedState> states = new HashMap<>();

    TestWorld tint(int index, int argb) {
        tints.put(index, argb);
        return this;
    }

    /** Every placement goes through here, so the extent and the cached empty space cannot fall behind the blocks. */
    private TestWorld place(int x, int y, int z, BakedState state) {
        blocks.put(key(x, y, z), state);
        fromX = Math.min(fromX, x);
        fromZ = Math.min(fromZ, z);
        toX = Math.max(toX, x);
        toZ = Math.max(toZ, z);
        empty = null;
        return this;
    }

    @Override
    public BakedState stateAt(int x, int y, int z) {
        reads++;
        return blocks.getOrDefault(key(x, y, z), BakedState.EMPTY);
    }

    @Override
    public int lightAt(int x, int y, int z) {
        lit++;
        return lights.getOrDefault(key(x, y, z), defaultLight);
    }

    /**
     * How many times a trace has asked how bright somewhere is, which is once or twice per texel it shades.
     *
     * <p>The counter to use for "was this pixel drawn at all". {@link #reads} counts <b>positions</b> now that a frame
     * remembers the blocks it has already looked at - see {@code SeenBlocks} - so it no longer tracks how many rays were
     * cast, while this is not remembered and still does.
     */
    int lit() {
        return lit;
    }

    private int lit;

    /**
     * Built over the blocks that were placed, widened so that a ray leaving the scene is skipped too.
     *
     * <p>The full height rather than the height of the terrain, because looking up at nothing is one of the cases
     * the skip has to get right, and the whole world above a hill is the space it is there to cross.
     */
    @Override
    public EmptySpace emptySpace() {
        EmptySpace built = empty;
        if (built == null) {
            int lowX = blocks.isEmpty() ? -64 : fromX - 64;
            int lowZ = blocks.isEmpty() ? -64 : fromZ - 64;
            int highX = blocks.isEmpty() ? 64 : toX + 64;
            int highZ = blocks.isEmpty() ? 64 : toZ + 64;

            EmptySpace.Builder building = EmptySpace.over(lowX, minY(), lowZ, highX, maxY(), highZ);
            for (long block : blocks.keySet()) {
                building.occupied(blockX(block), blockY(block), blockZ(block));
            }
            built = building.build();
            empty = built;
        }
        return built;
    }

    /**
     * How many blocks a trace has had to look at, which is the count a skip is meant to bring down.
     *
     * <p><b>Positions</b>, not questions: a frame remembers the blocks it has already looked at - see
     * {@code SeenBlocks} - so asking about one twice reaches here once. Still the right counter for whether a skip
     * crossed ground without examining it, and the wrong one for how many rays were cast. Use {@link #lit} for that.
     */
    int reads() {
        return reads;
    }

    @Override
    public int tintAt(int x, int y, int z, int index) {
        return tints.getOrDefault(index, 0xFFFFFFFF);
    }

    /** A plain gradient with nothing in it, so a test asserting on the sky gets one flat answer. */
    @Override
    public Sky sky() {
        return PLAIN_SKY;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 319;
    }

    @Override
    public Texture get(String name) {
        return textures.getOrDefault(name, solid(0xFFFF00FF));
    }

    private static long key(int x, int y, int z) {
        return (long) x & 0x3FFFFFF | ((long) z & 0x3FFFFFF) << 26 | (long) y << 52;
    }

    private static int blockX(long key) {
        return (int) (key << 38 >> 38);
    }

    private static int blockZ(long key) {
        return (int) (key << 12 >> 38);
    }

    private static int blockY(long key) {
        return (int) (key >> 52);
    }
}
