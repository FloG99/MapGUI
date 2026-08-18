package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The look direction has to be Bukkit's, or a capture is of somewhere slightly other than where the player was
 * looking. Yaw 0 faces south down +Z, yaw increases clockwise seen from above, positive pitch looks down.
 */
class CameraViewTest {

    private static final double EPSILON = 1e-9;

    private CameraView facing(float yaw, float pitch) {
        return new CameraView(0, 0, 0, yaw, pitch, CameraView.DEFAULT_FOV, 64);
    }

    private double[] forwardOf(float yaw, float pitch) {
        double[] forward = new double[3];
        facing(yaw, pitch).basis(forward, new double[3], new double[3]);
        return forward;
    }

    @Test
    void yawZeroFacesSouth() {
        double[] forward = forwardOf(0, 0);

        assertEquals(0, forward[0], EPSILON);
        assertEquals(0, forward[1], EPSILON);
        assertEquals(1, forward[2], EPSILON);
    }

    @Test
    void yawRunsClockwiseFromSouth() {
        assertEquals(-1, forwardOf(90, 0)[0], EPSILON, "yaw 90 faces west");
        assertEquals(-1, forwardOf(180, 0)[2], EPSILON, "yaw 180 faces north");
        assertEquals(1, forwardOf(270, 0)[0], EPSILON, "yaw 270 faces east");
    }

    @Test
    void positivePitchLooksDown() {
        assertEquals(-1, forwardOf(0, 90)[1], EPSILON);
        assertEquals(1, forwardOf(0, -90)[1], EPSILON);
    }

    /**
     * Facing south, west is on your right - the compass runs north, east, south, west clockwise. Getting this
     * backwards mirrors every frame, which looks entirely plausible until you read a sign in one.
     */
    @Test
    void screenRightIsWestWhenFacingSouth() {
        double[] right = new double[3];
        facing(0, 0).basis(new double[3], right, new double[3]);

        assertEquals(-1, right[0], EPSILON);
        assertEquals(0, right[1], EPSILON);
        assertEquals(0, right[2], EPSILON);
    }

    @Test
    void screenUpIsUpWhenLevel() {
        double[] up = new double[3];
        facing(0, 0).basis(new double[3], new double[3], up);

        assertEquals(0, up[0], EPSILON);
        assertEquals(1, up[1], EPSILON);
        assertEquals(0, up[2], EPSILON);
    }

    @Test
    void basisStaysOrthonormalAtAwkwardAngles() {
        for (float yaw : new float[]{0, 37, 90, 143, 180, 271, 359}) {
            for (float pitch : new float[]{-89, -45, 0, 12, 45, 89}) {
                double[] forward = new double[3];
                double[] right = new double[3];
                double[] up = new double[3];
                facing(yaw, pitch).basis(forward, right, up);

                String at = "yaw " + yaw + " pitch " + pitch;
                assertEquals(1, length(forward), 1e-6, at);
                assertEquals(1, length(right), 1e-6, at);
                assertEquals(1, length(up), 1e-6, at);
                assertEquals(0, dot(forward, right), 1e-6, at);
                assertEquals(0, dot(forward, up), 1e-6, at);
                assertEquals(0, dot(right, up), 1e-6, at);
            }
        }
    }

    /** Straight up is the degenerate case: any horizontal right will do, but it still has to be a real basis. */
    @Test
    void lookingStraightUpStillGivesABasis() {
        double[] forward = new double[3];
        double[] right = new double[3];
        double[] up = new double[3];
        facing(0, -90).basis(forward, right, up);

        assertEquals(1, length(right), 1e-6);
        assertEquals(0, dot(forward, right), 1e-6);
        assertEquals(0, right[1], EPSILON, "right stays horizontal");
    }

    /**
     * Which horizontal right, exactly, at yaw zero - because "any will do" is only true from in here.
     *
     * <p>Anything drawing a <b>flat surface</b> straight above or below the camera has to know: a mirror on a floor
     * projects its glass back into the frame, and can only do that by reproducing these axes. Free to be any particular
     * value here and load-bearing there, which is the kind of thing that moves under somebody tidying up a fallback - so
     * it is written down.
     *
     * <p>At yaw zero, looking up: right is west and screen-up is north. Looking down: right is west and screen-up is
     * south. Those are the two angles the client draws its own horizontal frames at, which is why {@code WallLayout}
     * takes north as up on a floor and south on a ceiling.
     */
    @Test
    void straightUpAndDownHaveStatedAxesAtYawZero() {
        double[] forward = new double[3];
        double[] right = new double[3];
        double[] up = new double[3];

        facing(0, -90).basis(forward, right, up);
        assertEquals(-1, right[0], EPSILON, "looking up, right is west");
        assertEquals(0, right[2], EPSILON);
        assertEquals(-1, up[2], EPSILON, "and screen-up is north");
        assertEquals(0, up[0], EPSILON);

        facing(0, 90).basis(forward, right, up);
        assertEquals(-1, right[0], EPSILON, "looking down, right is west too");
        assertEquals(0, right[2], EPSILON);
        assertEquals(1, up[2], EPSILON, "and screen-up is south");
        assertEquals(0, up[0], EPSILON);
    }

    /** The middle pixel of an odd-sized frame is exactly where the player looks, not half a pixel off. */
    @Test
    void theCenterPixelLooksWhereTheCameraDoes() {
        double[] direction = new double[3];
        facing(0, 0).direction(0, 0, 1, 1, direction);

        assertEquals(0, direction[0], EPSILON);
        assertEquals(0, direction[1], EPSILON);
        assertEquals(1, direction[2], EPSILON);
    }

    @Test
    void raysAreUnitLength() {
        double[] direction = new double[3];
        CameraView view = facing(53, 21);

        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                view.direction(x, y, 16, 16, direction);
                assertEquals(1, length(direction), 1e-9, x + "," + y);
            }
        }
    }

    /** Screen x has to grow toward screen right, and screen y downward. */
    @Test
    void pixelsSpreadTheRightWayRound() {
        CameraView view = facing(0, 0);
        double[] left = new double[3];
        double[] right = new double[3];
        double[] top = new double[3];
        double[] bottom = new double[3];

        view.direction(0, 8, 16, 16, left);
        view.direction(15, 8, 16, 16, right);
        view.direction(8, 0, 16, 16, top);
        view.direction(8, 15, 16, 16, bottom);

        // Facing south, screen right is west, which is -X.
        assertTrue(right[0] < left[0], "screen right should run toward west");
        assertTrue(top[1] > bottom[1], "screen up should run toward the sky");
    }

    /** A wider angle has to spread the same pixels further, or fov does nothing. */
    @Test
    void fieldOfViewWidensTheSpread() {
        double[] narrow = new double[3];
        double[] wide = new double[3];

        new CameraView(0, 0, 0, 0, 0, 30, 64).direction(0, 8, 16, 16, narrow);
        new CameraView(0, 0, 0, 0, 0, 120, 64).direction(0, 8, 16, 16, wide);

        assertTrue(Math.abs(wide[0]) > Math.abs(narrow[0]));
    }

    @Test
    void absurdParametersAreClamped() {
        assertEquals(10f, new CameraView(0, 0, 0, 0, 0, -5, 64).fov());
        assertEquals(170f, new CameraView(0, 0, 0, 0, 0, 900, 64).fov());
        assertEquals(1, new CameraView(0, 0, 0, 0, 0, 70, -3).maxDistance());
    }

    private static double length(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
