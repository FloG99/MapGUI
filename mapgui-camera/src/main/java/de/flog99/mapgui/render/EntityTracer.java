package de.flog99.mapgui.render;

/**
 * Ray against an entity's posed parts.
 *
 * <p>Entities are not in the block grid, so the voxel walk cannot find them. Rather than rotate every cube into
 * the world and intersect oriented boxes, the ray is turned into each part's own space and tested against an
 * axis-aligned one - the inverse transform costs a rotation of two vectors and hands back the hit face and the
 * texture coordinates for free. Nesting is composition of those inverses, so a cube on a turned head on a turned
 * body is two rotations and then the same slab test.
 *
 * <p>Cost is bounded by projected screen area, not by entity count: the caller only offers a pixel the entities
 * whose screen rect covers it, and an entity twenty blocks out covers a few dozen pixels of a map.
 */
final class EntityTracer {

    private static final float PIXEL = 1 / 16f;

    /**
     * Below this a texel is the transparent part of a texture rather than a drawn one.
     *
     * <p>The client's own number: its entity pipeline discards under an {@code ALPHA_CUTOUT} of {@code 0.1}, which
     * is this out of 255. Blocks cut at half, entities at a tenth, and using the block figure here threw away every
     * shell drawn at a third.
     */
    private static final int MIN_ALPHA = 26;

    private final Textures textures;

    /** Written by the walk, so it does not have to allocate a result per test. */
    private double distance;
    private int color;
    private Direction face;

    /** The entity being tested, held for the walk rather than threaded through every frame of it. */
    private EntitySnapshot entity;
    private Texture texture;
    private int tint;
    private boolean culled;
    private double scale;
    private double from;
    private double nearest;
    private double headXRot;
    private double headYRot;
    private boolean found;

    /** The ray in the entity's own space, kept so walking to the surface behind one costs no setup. */
    private double rayX;
    private double rayY;
    private double rayZ;
    private double stepX;
    private double stepY;
    private double stepZ;

    /** Both crossings of a cube, set by {@link #slab}, which finds the faces along with the distances. */
    private double slabEnter;
    private double slabExit;
    private Direction slabFace;
    private Direction slabExitFace;

    EntityTracer(Textures textures) {
        this.textures = textures;
    }

    double distance() {
        return distance;
    }

    int color() {
        return color;
    }

    /**
     * Which side of the model the ray met, in the entity's own space rather than the world's.
     *
     * <p>Good enough to shade with. The part has been turned by the time it is tested, so a mob's "north" is
     * whichever way it happens to be facing, and using it for face shading darkens its sides relative to its front
     * regardless of where it stands. That reads as shape, which is what the shading is for.
     */
    Direction face() {
        return face;
    }

    /**
     * Nearest drawn texel of one entity along the ray, or false if the ray misses it entirely.
     *
     * @param limit ignore anything at or beyond this, which is where the blocks already stopped the ray
     */
    boolean first(EntitySnapshot entity, double originX, double originY, double originZ,
                  double dx, double dy, double dz, double limit) {

        this.entity = entity;
        texture = textures.get(entity.texture());
        tint = entity.tint();
        culled = entity.model().culled();

        // Model to world is 180 plus the yaw - the skin unwrap puts the face on the model's -Z side, so local
        // front is north and a player at yaw 0 faces south, half a circle round. This is the inverse of that,
        // because the ray travels the other way. Both terms matter: drop the 180 and everybody is back to front,
        // negate the yaw wrongly and only 0 and 180 come out right.
        double bodyCos = Math.cos(Math.toRadians(-180 - entity.bodyYaw()));
        double bodySin = Math.sin(Math.toRadians(-180 - entity.bodyYaw()));

        // The head's own pose, added to whatever rest rotation it was baked with. Not the negation of the yaw
        // term above: Minecraft's pitch counts downward, so taking the sign from the yaw's lead tilts every head
        // the wrong way and a mob looking at its feet is drawn studying the sky.
        headYRot = Math.toRadians(entity.bodyYaw() - entity.headYaw());
        headXRot = Math.toRadians(-entity.pitch());

        // Into entity space: relative to the feet, then turned against the body yaw. Kept, because every further
        // surface of the same entity is walked with the same ray and there is nothing to work out twice.
        scale = entity.scale() * PIXEL;
        double relX = (originX - entity.x()) / scale;
        double relY = (originY - entity.y()) / scale;
        double relZ = (originZ - entity.z()) / scale;

        rayX = relX * bodyCos - relZ * bodySin;
        rayY = relY;
        rayZ = relX * bodySin + relZ * bodyCos;
        stepX = dx * bodyCos - dz * bodySin;
        stepY = dy;
        stepZ = dx * bodySin + dz * bodyCos;

        from = 0;
        return walk(limit);
    }

    /**
     * The next surface of the same entity behind the one just reported, or false once there is nothing more.
     *
     * <p>What a mob that can be seen into needs: a slime's outer shell is one cube and its inner one another, both
     * in the one mesh, so reporting only the nearest of the two draws the shell and never looks for what the shell
     * contains. The cursor stays in the entity's own space rather than being handed back in blocks, because a
     * distance that went out multiplied by the scale and came back divided by it can land a bit short of where it
     * started - and a cursor that fails to advance reports the same texel until the fragment list is full of it.
     */
    boolean next(double limit) {
        from = nearest;
        return walk(limit);
    }

    /** The kept ray against every part, nearest drawn texel beyond the cursor winning. */
    private boolean walk(double limit) {
        nearest = limit / scale;
        found = false;

        for (MeshPart part : entity.model().parts()) {
            walk(part, rayX, rayY, rayZ, stepX, stepY, stepZ);
        }

        distance = found ? nearest * scale : limit;
        return found;
    }

    /**
     * The ray against one part and everything under it, with the ray carried in that part's own space.
     *
     * <p>The distance parameter survives the transform: turning and translating leave it alone, and dividing the
     * direction by the same scale as the origin means a scaled part reports the same {@code t} as its parent
     * would. So there is nothing to correct on the way back out.
     */
    private void walk(MeshPart part, double ox, double oy, double oz, double dx, double dy, double dz) {
        ox -= part.x();
        oy -= part.y();
        oz -= part.z();

        double xRot = part.xRot();
        double yRot = part.yRot();
        double zRot = part.zRot();
        if (part.head()) {
            xRot += headXRot;
            yRot += headYRot;
        }

        if (zRot != 0) {
            double cos = Math.cos(zRot);
            double sin = Math.sin(zRot);
            double x = ox * cos + oy * sin;
            oy = -ox * sin + oy * cos;
            ox = x;
            double rx = dx * cos + dy * sin;
            dy = -dx * sin + dy * cos;
            dx = rx;
        }
        if (yRot != 0) {
            double cos = Math.cos(yRot);
            double sin = Math.sin(yRot);
            double x = ox * cos - oz * sin;
            oz = ox * sin + oz * cos;
            ox = x;
            double rx = dx * cos - dz * sin;
            dz = dx * sin + dz * cos;
            dx = rx;
        }
        if (xRot != 0) {
            double cos = Math.cos(xRot);
            double sin = Math.sin(xRot);
            double y = oy * cos + oz * sin;
            oz = -oy * sin + oz * cos;
            oy = y;
            double ry = dy * cos + dz * sin;
            dz = -dy * sin + dz * cos;
            dy = ry;
        }
        if (part.xScale() != 1 || part.yScale() != 1 || part.zScale() != 1) {
            ox /= part.xScale();
            oy /= part.yScale();
            oz /= part.zScale();
            dx /= part.xScale();
            dy /= part.yScale();
            dz /= part.zScale();
        }

        for (MeshCube cube : part.cubes()) {
            if (!slab(cube, ox, oy, oz, dx, dy, dz)) {
                continue;
            }

            // The near side first and then the far one, unless this model's render type culls. Vanilla draws mob
            // models with culling off, so where the near side of a cube is a transparent texel the far side shows
            // through it - and for a chicken's leg that is the whole leg, a box whose only drawn texels are one
            // column on the back face and the foot on the underside. Sampling the entry face alone loses it from
            // every angle in front of the bird.
            if (!draw(cube, slabFace, slabEnter, ox, oy, oz, dx, dy, dz) && !culled) {
                draw(cube, slabExitFace, slabExit, ox, oy, oz, dx, dy, dz);
            }
        }

        for (MeshPart child : part.children()) {
            walk(child, ox, oy, oz, dx, dy, dz);
        }
    }

    /**
     * One crossing of one cube, kept if it draws something nearer than anything so far.
     *
     * @return whether that side drew at all, which is what decides if the far one is worth asking about
     */
    private boolean draw(MeshCube cube, Direction side, double at, double ox, double oy, double oz, double dx, double dy, double dz) {
        if (at <= from || at >= nearest) return false;

        float[] corners = cube.face(side);
        if (corners == null) return false;

        int texel = sample(cube, side, corners, ox + dx * at, oy + dy * at, oz + dz * at);
        if ((texel >>> 24) < MIN_ALPHA) return false;

        nearest = at;
        color = tint == 0 ? texel : tinted(texel);
        face = side;
        found = true;
        return true;
    }

    /** A dyed layer, multiplied the way a block's biome tint is - a sheep's fleece over one white wool texture. */
    private int tinted(int texel) {
        int red = (texel >> 16 & 0xFF) * (tint >> 16 & 0xFF) / 255;
        int green = (texel >> 8 & 0xFF) * (tint >> 8 & 0xFF) / 255;
        int blue = (texel & 0xFF) * (tint & 0xFF) / 255;
        return texel & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * The same slab test the block models use, over one cube in entity pixels.
     *
     * <p>Written out per axis rather than over three-element arrays: a cube is tested once per pixel that could see
     * it, so a frame of mobs allocated tens of thousands of arrays to hold numbers it used immediately.
     *
     * <p>Both crossings come out of it, because a ray meets a box's surface exactly twice and vanilla culls neither
     * of the two - so the entry and the exit are the complete list of sides this ray could draw.
     *
     * @return whether it met the cube in front of the ray at all, so a miss and a hit behind the current best cost
     *         the same and neither disturbs the result
     */
    private boolean slab(MeshCube cube, double ox, double oy, double oz, double dx, double dy, double dz) {
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        int enterAxis = -1;
        int exitAxis = -1;
        boolean enterLow = true;
        boolean exitLow = true;

        for (int i = 0; i < 3; i++) {
            double origin = i == 0 ? ox : i == 1 ? oy : oz;
            double direction = i == 0 ? dx : i == 1 ? dy : dz;
            double lower = i == 0 ? cube.minX() : i == 1 ? cube.minY() : cube.minZ();
            double upper = i == 0 ? cube.maxX() : i == 1 ? cube.maxY() : cube.maxZ();

            if (Math.abs(direction) < 1e-12) {
                if (origin < lower || origin > upper) return false;
                continue;
            }

            double inverse = 1 / direction;
            double first = (lower - origin) * inverse;
            double second = (upper - origin) * inverse;
            if (Math.min(first, second) > enter) {
                enter = Math.min(first, second);
                enterAxis = i;
                enterLow = first <= second;
            }
            if (Math.max(first, second) < exit) {
                exit = Math.max(first, second);
                exitAxis = i;
                exitLow = first > second;
            }
        }

        if (exit < enter || exit <= from || enterAxis < 0 || exitAxis < 0 || enter >= nearest) return false;

        slabEnter = enter;
        slabExit = exit;
        slabFace = sideOf(enterAxis, enterLow);
        slabExitFace = sideOf(exitAxis, exitLow);
        return true;
    }

    private static Direction sideOf(int axis, boolean low) {
        return switch (axis) {
            case 0 -> low ? Direction.WEST : Direction.EAST;
            case 1 -> low ? Direction.DOWN : Direction.UP;
            default -> low ? Direction.NORTH : Direction.SOUTH;
        };
    }

    /**
     * Where on the face the hit landed, read through the four corner UVs vanilla gave that face.
     *
     * <p>Bilinear over the corners rather than a patch offset plus flip flags. The quad is a rectangle in both
     * spaces, so the interpolation is exact, and a face the model mirrored or laid on its side needs nothing said
     * about it - the corners already are the answer.
     */
    private int sample(MeshCube cube, Direction face, float[] corners, double hitX, double hitY, double hitZ) {
        double across = Math.clamp(cube.across(face, hitX, hitY, hitZ), 0, 0.9999);
        double down = Math.clamp(cube.down(face, hitX, hitY, hitZ), 0, 0.9999);

        int topLeft = MeshCube.corner(false, false) * 2;
        int topRight = MeshCube.corner(true, false) * 2;
        int bottomLeft = MeshCube.corner(false, true) * 2;
        int bottomRight = MeshCube.corner(true, true) * 2;

        double u = mix(mix(corners[topLeft], corners[topRight], across), mix(corners[bottomLeft], corners[bottomRight], across), down);
        double v = mix(mix(corners[topLeft + 1], corners[topRight + 1], across), mix(corners[bottomLeft + 1], corners[bottomRight + 1], across), down);

        // Texture#sample works in sixteenths of its own width, and these are normalized.
        return texture.sample((float) (u * 16), (float) (v * 16), 0);
    }

    private static double mix(double from, double to, double by) {
        return from + (to - from) * by;
    }
}
