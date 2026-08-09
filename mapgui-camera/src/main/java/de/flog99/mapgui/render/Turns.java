package de.flog99.mapgui.render;

/**
 * The rotation arithmetic a pose needs, as 3x3 matrices in row-major order.
 *
 * <p>Here because two conventions have to meet. A {@link MeshPart} states its rotation the way vanilla's
 * {@code ModelPart} does - three angles applied Z, then Y, then X - while an item model's {@code display} block
 * states its own the other way round, X then Y then Z. Neither can be converted into the other by reordering the
 * numbers, so the way across is to build the matrix and take the angles back out of it.
 *
 * <p>Matrices rather than quaternions. Everything here either composes two rotations, turns one vector, or hands
 * back a triple of angles, and all three are shorter to read as a matrix than as a quaternion - with no library to
 * depend on, which this module has none of by design.
 */
final class Turns {

    private Turns() {
    }

    /** A fresh identity, rather than a shared constant somebody could write into. */
    static float[] none() {
        return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
    }

    static float[] x(double radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new float[]{1, 0, 0, 0, cos, -sin, 0, sin, cos};
    }

    static float[] y(double radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new float[]{cos, 0, sin, 0, 1, 0, -sin, 0, cos};
    }

    static float[] z(double radians) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        return new float[]{cos, -sin, 0, sin, cos, 0, 0, 0, 1};
    }

    /**
     * A quaternion as a rotation matrix, in the order the assets write one: {@code x, y, z, w}.
     *
     * <p>Which is how an item definition states the turn it puts a shape the client draws in code through - the
     * {@code left_rotation} and {@code right_rotation} either side of its scale.
     */
    static float[] quaternion(float x, float y, float z, float w) {
        return new float[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
                2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
                2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)
        };
    }

    static float[] times(float[] left, float[] right) {
        float[] out = new float[9];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                out[row * 3 + column] = left[row * 3] * right[column]
                        + left[row * 3 + 1] * right[3 + column]
                        + left[row * 3 + 2] * right[6 + column];
            }
        }
        return out;
    }

    static float[] apply(float[] turn, float x, float y, float z) {
        return new float[]{
                turn[0] * x + turn[1] * y + turn[2] * z,
                turn[3] * x + turn[4] * y + turn[5] * z,
                turn[6] * x + turn[7] * y + turn[8] * z
        };
    }

    /** A part's three angles as one rotation, in the order vanilla's {@code ModelPart} applies them. */
    static float[] part(float xRot, float yRot, float zRot) {
        return times(z(zRot), times(y(yRot), x(xRot)));
    }

    /** The order an item model's {@code display} rotation is applied in, which is the other one. */
    static float[] display(double xDegrees, double yDegrees, double zDegrees) {
        return times(x(Math.toRadians(xDegrees)), times(y(Math.toRadians(yDegrees)), z(Math.toRadians(zDegrees))));
    }

    /**
     * The three angles a {@link MeshPart} would need to be turned this way, as {@code xRot, yRot, zRot} in radians.
     *
     * <p>The inverse of {@link #part}, and exact for any rotation: writing out {@code Rz Ry Rx} leaves the Y angle in
     * one entry on its own and the other two as ratios of a row and a column, so all three come straight back out.
     *
     * <p>Except looking straight along Y, where the X and Z rotations turn about the same axis and only their sum is
     * determined. Any split of it is the same rotation, so this puts all of it in Z.
     */
    static float[] angles(float[] turn) {
        float sinY = -turn[6];
        if (Math.abs(sinY) > 0.99999f) {
            return new float[]{0, (float) Math.copySign(Math.PI / 2, sinY), (float) Math.atan2(-turn[1], turn[4])};
        }

        return new float[]{
                (float) Math.atan2(turn[7], turn[8]),
                (float) Math.asin(sinY),
                (float) Math.atan2(turn[3], turn[0])
        };
    }

    /**
     * The same rotation seen from this module's own space rather than vanilla's.
     *
     * <p>The two differ by {@code diag(-1, -1, 1)} - a half turn about Z, which is what {@code LivingEntityRenderer}
     * applies to every model before it draws - and a rotation moves between spaces by conjugation. Since the
     * conjugating matrix is its own inverse, that comes to flipping the sign of the four entries where exactly one
     * of the row and the column is the Z one.
     */
    static float[] mirrored(float[] turn) {
        float[] out = turn.clone();
        for (int index : new int[]{2, 5, 6, 7}) {
            out[index] = -out[index];
        }
        return out;
    }

    /**
     * The same rotation seen from the frame a block model arrives in.
     *
     * <p>That frame is the model's own turned a half circle about Y - see {@link BlockItems}, which mirrors X and Z as
     * it builds each box. So anything the client states against a block model's own axes has to come through the same
     * turn before it can be applied here: an item hung in a frame is pushed along the client's +Z and this module's
     * -Z, and turned the other way round about the axis it is pushed along.
     *
     * <p>The half turn is its own inverse, so this is the whole of the conversion in both directions: the four entries
     * where exactly one of the row and the column is the Y one change sign.
     */
    static float[] halfTurned(float[] turn) {
        float[] out = turn.clone();
        for (int index : new int[]{1, 3, 5, 7}) {
            out[index] = -out[index];
        }
        return out;
    }

    /** And one offset through the same turn, which is what mirroring X and Z comes to. */
    static float[] halfTurned(float x, float y, float z) {
        return new float[]{-x, y, -z};
    }
}
