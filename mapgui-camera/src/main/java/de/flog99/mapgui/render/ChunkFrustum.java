package de.flog99.mapgui.render;

/**
 * Whether a chunk column is in front of the camera at all, so a capture only copies the ones a ray can reach.
 *
 * <p>Snapshotting a chunk copies its whole column of blocks and light, and the square around the camera is far more
 * than a frame needs - at 96 blocks it is 225 chunks on the main thread.
 *
 * <p>Three tests, each one-sided the same way: keeping a chunk no ray reaches costs one snapshot, dropping one a ray
 * does reach puts a hole in the picture. So each is exact where that is cheap and loose where it is not.
 *
 * <p><b>The horizontal cone</b> is measured from the four corners of the frame rather than from forward. As pitch
 * steepens, forward's horizontal part shrinks by {@code cos(pitch)} while screen-up's grows by {@code sin(pitch)},
 * so rays toward the top and bottom of a steep frame swing a long way sideways. Horizontal direction is bilinear in
 * screen position, so four samples bound it exactly.
 *
 * <p><b>Range</b> is a real 3D distance, which matters because the square a capture walks has the full ray distance
 * as its half-width and so puts its corners half again too far out.
 *
 * <p><b>Height</b> is what makes a steep angle cheap. The four side planes of the pyramid all pass through the eye,
 * and each solved for Y bounds how high a ray can be over a given X and Z - so a column whose Y range is entirely
 * outside the world holds nothing any ray could hit. Looking straight down that drops everything past roughly one
 * world height out, which is exactly where the cone gives up.
 */
public final class ChunkFrustum {

    /** A chunk's horizontal half-diagonal, eight blocks each way from the middle. */
    private static final double CHUNK_REACH = 8 * Math.sqrt(2);

    /**
     * Blocks of slack on both the height bound and the range. A ray point lies somewhere inside a block, which
     * reaches a corner further out, and {@link RayCaster} then reads that block's neighbour for face culling and
     * for the light falling on the face - so the chunk a ray needs is not always the chunk the ray is in.
     */
    private static final double BLOCK_SLACK = 1 + Math.sqrt(3);

    /** One side of the pyramid, as a plane through the eye whose normal points inward. */
    private record Side(double x, double y, double z) {
    }

    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final double forwardX;
    private final double forwardZ;
    private final double halfAngle;
    private final boolean everything;
    private final double range;
    private final int worldMinY;
    private final int worldMaxY;
    private final Side[] sides;

    /** How long each side vector is, so a radius can be measured against a plane that is not a unit normal. */
    private final double[] sideLengths;

    /**
     * @param worldMinY lowest block Y of the world being captured, {@code worldMaxY} the highest. The height test
     *                  is against the world rather than against nothing, since a Y range that misses it entirely
     *                  is a column with nothing in it to see.
     */
    public ChunkFrustum(CameraView view, int worldMinY, int worldMaxY) {
        this.eyeX = view.x();
        this.eyeY = view.y();
        this.eyeZ = view.z();
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.range = view.maxDistance() + BLOCK_SLACK;

        double yaw = Math.toRadians(view.yaw());
        this.forwardX = -Math.sin(yaw);
        this.forwardZ = Math.cos(yaw);

        double[] forward = new double[3];
        double[] right = new double[3];
        double[] up = new double[3];
        view.basis(forward, right, up);

        double tanHalf = Math.tan(Math.toRadians(view.fov()) / 2);
        this.sides = new Side[]{
                side(tanHalf, forward, right, 1),
                side(tanHalf, forward, right, -1),
                side(tanHalf, forward, up, 1),
                side(tanHalf, forward, up, -1)
        };
        this.sideLengths = new double[sides.length];
        for (int i = 0; i < sides.length; i++) {
            Side side = sides[i];
            sideLengths[i] = Math.sqrt(side.x() * side.x() + side.y() * side.y() + side.z() * side.z());
        }

        double widest = 0;
        boolean degenerate = false;

        for (int cornerX = -1; cornerX <= 1; cornerX += 2) {
            for (int cornerY = -1; cornerY <= 1; cornerY += 2) {
                double sx = cornerX * tanHalf;
                double sy = cornerY * tanHalf;
                double dx = forward[0] + right[0] * sx + up[0] * sy;
                double dz = forward[2] + right[2] * sx + up[2] * sy;

                double length = Math.sqrt(dx * dx + dz * dz);
                if (length < 1e-6) {
                    // A corner ray pointing straight up has no horizontal direction to compare, which means the
                    // frame contains the vertical axis and the horizontal fan is the whole circle.
                    degenerate = true;
                    continue;
                }

                double cos = Math.clamp((dx * forwardX + dz * forwardZ) / length, -1, 1);
                widest = Math.max(widest, Math.acos(cos));
            }
        }

        this.everything = degenerate || widest >= Math.PI / 2;
        this.halfAngle = widest;
    }

    /**
     * The plane holding one edge of the frame, as {@code tanHalf * forward} plus or minus a screen axis.
     *
     * <p>A ray is {@code forward + right * sx + up * sy} for {@code |sx|, |sy| <= tanHalf}, and the basis is
     * orthonormal, so dotting one against this comes out as {@code tanHalf +- sx} - non-negative for exactly the
     * rays inside the frame. Which is what makes the four of them the frustum.
     */
    private static Side side(double tanHalf, double[] forward, double[] axis, int sign) {
        return new Side(tanHalf * forward[0] + sign * axis[0],
                tanHalf * forward[1] + sign * axis[1],
                tanHalf * forward[2] + sign * axis[2]);
    }

    /**
     * Whether anything inside a ball of {@code radius} around this point could land in the frame.
     *
     * <p>The same four planes, asked about something far smaller than a sixteen-block column - which is what stops a
     * mob being built for every column the frame merely clips. Conservative like the column test: a ball that
     * touches the frame at all is kept.
     *
     * <p><b>Not</b> occlusion. Only the trace knows what is in front of what, and by then it is off-thread.
     */
    public boolean mightSee(double x, double y, double z, double radius) {
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;

        double reach = range + radius;
        if (dx * dx + dy * dy + dz * dz > reach * reach) return false;
        if (everything) return true;

        // Outside a plane by more than the ball reaches along its normal, and nothing in it can be in frame. The
        // sides are not unit vectors, so each carries its own length to measure that slack against.
        for (int i = 0; i < sides.length; i++) {
            if (sides[i].x() * dx + sides[i].y() * dy + sides[i].z() * dz < -radius * sideLengths[i]) {
                return false;
            }
        }
        return true;
    }

    public boolean mightSee(int chunkX, int chunkZ) {
        double fromX = (chunkX << 4) - eyeX;
        double fromZ = (chunkZ << 4) - eyeZ;
        double toX = fromX + 16;
        double toZ = fromZ + 16;

        // Horizontal distance to the nearest point of the column, zero if the eye is over it.
        double gapX = Math.max(0, Math.max(fromX, -toX));
        double gapZ = Math.max(0, Math.max(fromZ, -toZ));
        double nearest = gapX * gapX + gapZ * gapZ;
        if (nearest > range * range) return false;

        return withinCone(chunkX, chunkZ)
                && heightOverlapsWorld(fromX, toX, fromZ, toZ, Math.sqrt(range * range - nearest));
    }

    private boolean withinCone(int chunkX, int chunkZ) {
        if (everything) return true;

        double toX = (chunkX << 4) + 8 - eyeX;
        double toZ = (chunkZ << 4) + 8 - eyeZ;
        double distance = Math.sqrt(toX * toX + toZ * toZ);
        if (distance <= CHUNK_REACH) return true;

        // Widened by the angle the chunk subtends, so one that only clips the edge of the cone is kept.
        double limit = halfAngle + Math.asin(Math.min(1, CHUNK_REACH / distance));
        if (limit >= Math.PI) return true;

        return (toX * forwardX + toZ * forwardZ) / distance >= Math.cos(limit);
    }

    /**
     * Whether the Y a ray can still be at over this column overlaps the world at all.
     *
     * @param reach how far above or below the eye a ray can be once it is out this far, from the range cap alone.
     *              Straight up is inside every frustum that contains it and no side plane bounds it, so without
     *              this the fallback would be the whole world.
     */
    private boolean heightOverlapsWorld(double fromX, double toX, double fromZ, double toZ, double reach) {
        double lowest = -reach;
        double highest = reach;

        for (Side side : sides) {
            // A side that does not lean at all is the left or right of a level frame, which says nothing about Y.
            if (Math.abs(side.y()) < 1e-9) continue;

            // The side's own inequality solved for Y, read at the corner of the column where it concedes the most.
            double slack = (side.x() > 0 ? side.x() * toX : side.x() * fromX)
                    + (side.z() > 0 ? side.z() * toZ : side.z() * fromZ);

            if (side.y() < 0) {
                highest = Math.min(highest, slack / -side.y());
            } else {
                lowest = Math.max(lowest, -slack / side.y());
            }
        }

        double low = eyeY + lowest - BLOCK_SLACK;
        double high = eyeY + highest + BLOCK_SLACK;

        // An inverted range means no part of the frustum is over this column at all, whatever the world height is -
        // which is how a column off the diagonal of a steep frame gets dropped.
        return low <= high && high >= worldMinY && low <= worldMaxY;
    }
}
