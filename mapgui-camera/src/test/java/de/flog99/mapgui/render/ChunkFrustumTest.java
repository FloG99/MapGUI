package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Culling a chunk a ray reaches puts a hole in the picture, so this marches every ray of a frame and checks the
 * chunks it enters were all kept. Uses the real {@link CameraView} for directions rather than a second copy of the
 * basis arithmetic - a hand-rolled copy with two signs wrong is what first "found" thousands of false failures.
 *
 * <p>The sweep is in three dimensions, because the frustum culls in three. A ray only needs the column it is over
 * while it is still inside the world: above the build limit or below bedrock there is no block for it to hit, and
 * that is the whole licence the height test operates on. So the march stops at the world's edges as well as at the
 * range cap, and every column it did reach has to survive.
 */
class ChunkFrustumTest {

    private static final int DISTANCE = 96;
    private static final float FOV = 70;
    private static final double EYE_X = 8.5;
    private static final double EYE_Y = 70;
    private static final double EYE_Z = 8.5;
    private static final int RADIUS_CHUNKS = (DISTANCE >> 4) + 1;
    private static final int ACROSS = RADIUS_CHUNKS * 2 + 1;

    /** A modern overworld: the deepest block and the highest one, both inclusive. */
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 319;

    /** Half a block at a time, so nothing a ray crosses is stepped over. */
    private static final double STEP = 0.5;

    private static CameraView at(float yaw, float pitch) {
        return new CameraView(EYE_X, EYE_Y, EYE_Z, yaw, pitch, FOV, DISTANCE);
    }

    private static ChunkFrustum frustumFor(CameraView view) {
        return new ChunkFrustum(view, MIN_Y, MAX_Y);
    }

    /**
     * Marches every pixel of a {@code size} square frame and fails if the frustum drops a column any of them was
     * over while inside the world.
     *
     * <p>Collected first and checked after, rather than asserting per step: the same column is entered by thousands
     * of rays, and building a failure message for each of them is most of the cost of the test.
     */
    private static void assertKeepsEveryColumnAnyRayEnters(CameraView view, int size, String where) {
        boolean[] entered = new boolean[ACROSS * ACROSS];
        double[] direction = new double[3];

        int eyeChunkX = (int) Math.floor(view.x()) >> 4;
        int eyeChunkZ = (int) Math.floor(view.z()) >> 4;

        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                view.direction(px, py, size, size, direction);

                for (double t = 0; t <= view.maxDistance(); t += STEP) {
                    int chunkX = ((int) Math.floor(view.x() + direction[0] * t) >> 4) - eyeChunkX;
                    int chunkZ = ((int) Math.floor(view.z() + direction[2] * t) >> 4) - eyeChunkZ;
                    // Horizontal position is monotone in t as well, so leaving the square is the end of the ray.
                    if (Math.abs(chunkX) > RADIUS_CHUNKS || Math.abs(chunkZ) > RADIUS_CHUNKS) break;

                    // Skipped rather than stopped at, since an eye above the build limit is looking down into the
                    // world from outside it and its rays enter partway along.
                    int y = (int) Math.floor(view.y() + direction[1] * t);
                    if (y < MIN_Y || y > MAX_Y) continue;

                    entered[(chunkZ + RADIUS_CHUNKS) * ACROSS + chunkX + RADIUS_CHUNKS] = true;
                }
            }
        }

        ChunkFrustum frustum = frustumFor(view);
        for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS; cz++) {
            for (int cx = -RADIUS_CHUNKS; cx <= RADIUS_CHUNKS; cx++) {
                if (!entered[(cz + RADIUS_CHUNKS) * ACROSS + cx + RADIUS_CHUNKS]) {
                    continue;
                }

                assertTrue(frustum.mightSee(eyeChunkX + cx, eyeChunkZ + cz),
                        where + " drops chunk " + (eyeChunkX + cx) + "," + (eyeChunkZ + cz));
            }
        }
    }

    /** How much of the square around the camera survives, which is the number the whole class exists to lower. */
    private static int kept(ChunkFrustum frustum) {
        int kept = 0;
        for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS; cz++) {
            for (int cx = -RADIUS_CHUNKS; cx <= RADIUS_CHUNKS; cx++) {
                if (frustum.mightSee(cx, cz)) {
                    kept++;
                }
            }
        }
        return kept;
    }

    @Test
    void everyColumnAnyRayEntersIsKept() {
        for (float pitch : new float[]{-90, -80, -54, -35, -12, 0, 12, 35, 54, 80, 90}) {
            for (float yaw = 0; yaw < 360; yaw += 11) {
                assertKeepsEveryColumnAnyRayEnters(at(yaw, pitch), 32, "yaw " + yaw + " pitch " + pitch);
            }
        }
    }

    /** A wide angle spreads further, so this has to hold at the extremes of what fov allows too. */
    @Test
    void holdsAtTheWidestAndNarrowestFieldOfView() {
        for (float fov : new float[]{10, 30, 110, 170}) {
            for (float pitch : new float[]{-90, -70, -30, 0, 30, 70, 90}) {
                CameraView view = new CameraView(EYE_X, EYE_Y, EYE_Z, 37, pitch, fov, DISTANCE);
                assertKeepsEveryColumnAnyRayEnters(view, 24, "fov " + fov + " pitch " + pitch);
            }
        }
    }

    /**
     * The height test measures against the world, so where the eye sits inside it changes every answer - and near
     * the floor or the build limit is where it culls hardest and so where it would drop a visible column.
     */
    @Test
    void holdsFromAnEyeAnywhereInTheWorld() {
        for (double eyeY : new double[]{MIN_Y + 0.5, MIN_Y + 20, 0, 200, MAX_Y - 1.5, MAX_Y + 40}) {
            for (float pitch : new float[]{-90, -60, -20, 0, 20, 60, 90}) {
                CameraView view = new CameraView(EYE_X, eyeY, EYE_Z, 214, pitch, FOV, DISTANCE);
                assertKeepsEveryColumnAnyRayEnters(view, 24, "eye " + eyeY + " pitch " + pitch);
            }
        }
    }

    /** Off the origin as well, since a chunk boundary is the one place an off-by-one hides. */
    @Test
    void holdsAwayFromTheOrigin() {
        for (double eyeX : new double[]{-1023.0, -1024.0, -1024.999, 4096.0, 4111.999}) {
            for (float pitch : new float[]{-70, -25, 0, 25, 70, 90}) {
                CameraView view = new CameraView(eyeX, EYE_Y, -eyeX + 7, 133, pitch, FOV, DISTANCE);
                assertKeepsEveryColumnAnyRayEnters(view, 20, "eye " + eyeX + " pitch " + pitch);
            }
        }
    }

    /** It has to actually cull, or it is only costing arithmetic. */
    @Test
    void aLevelViewKeepsWellUnderHalfTheSquare() {
        int kept = kept(frustumFor(at(0, 0)));
        assertTrue(kept < ACROSS * ACROSS / 2, "kept " + kept + " of " + ACROSS * ACROSS);
    }

    /**
     * Straight down there is no horizontal direction left to cull by and the cone gives up entirely, which used to
     * mean the whole square got snapshotted. The frame still only spans so many degrees, so the far columns are
     * below the world by the time a ray could be over them.
     */
    @Test
    void aViewStraightDownDropsTheFarColumnsAnyway() {
        ChunkFrustum frustum = frustumFor(at(0, 90));

        assertTrue(frustum.mightSee(0, 0), "the column under the camera");
        assertTrue(frustum.mightSee(1, 0), "the column beside it");
        assertFalse(frustum.mightSee(RADIUS_CHUNKS, RADIUS_CHUNKS), "the far corner");
        assertTrue(kept(frustum) < ACROSS * ACROSS / 2, "kept " + kept(frustum) + " of " + ACROSS * ACROSS);
    }

    /** The square a capture walks has the ray distance as its half-width, so its corners are out of reach. */
    @Test
    void theCornersOfTheSquareAreOutOfRange() {
        for (float pitch : new float[]{-90, 0, 90}) {
            ChunkFrustum frustum = frustumFor(at(0, pitch));
            assertFalse(frustum.mightSee(RADIUS_CHUNKS, RADIUS_CHUNKS), "pitch " + pitch);
            assertFalse(frustum.mightSee(-RADIUS_CHUNKS, RADIUS_CHUNKS), "pitch " + pitch);
        }
    }

    /**
     * Past about fifty degrees of pitch the cone gives up completely - a steeply angled frame really does contain
     * rays going most of the way round the compass - and the entire square used to be copied. Height is the only
     * thing culling anything up there, and it is the reason those pitches are no longer the expensive ones.
     */
    @Test
    void aSteepViewNoLongerTakesTheWholeSquare() {
        for (float pitch : new float[]{-90, -80, -70, -60, 60, 70, 80, 90}) {
            for (float yaw : new float[]{0, 23, 137}) {
                int kept = kept(frustumFor(at(yaw, pitch)));
                assertTrue(kept < ACROSS * ACROSS / 2,
                        "pitch " + pitch + " yaw " + yaw + " kept " + kept + " of " + ACROSS * ACROSS);
            }
        }
    }

    /** Above the build limit, a level frame's lowest ray is still 75 blocks over it - that whole capture is sky. */
    @Test
    void anEyeAboveTheBuildLimitLookingLevelKeepsNothing() {
        CameraView view = new CameraView(EYE_X, MAX_Y + 80, EYE_Z, 45, 0, FOV, DISTANCE);
        assertTrue(kept(frustumFor(view)) == 0, "kept " + kept(frustumFor(view)));
    }
}
