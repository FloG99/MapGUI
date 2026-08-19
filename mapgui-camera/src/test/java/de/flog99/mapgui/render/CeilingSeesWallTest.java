package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mirror on the ceiling showing a mirror on the wall below it, all of it.
 *
 * <p>Reported as a band of the wall mirror missing along one edge, with the block behind it showing through, and only
 * from some standing positions. The picture was not being clipped by anything in the world - it was never <b>offered</b>
 * to those rays. {@link EntityScreen} works out a pixel rect per entity and only tests a ray against the entities whose
 * rect covers it, and that rect took the sphere's radius over its depth: the extent of a sphere sitting on the frame's
 * own axis. Anything off to one side is further from the camera than its depth says and its silhouette is wider than
 * that, by {@code 1 / cos squared} of the angle - double at 45 degrees off, which is where a ceiling mirror looks at a
 * wall.
 *
 * <p>Most entities never noticed because their bounding sphere is a generous bound to begin with. A wall's picture is a
 * flat square whose sphere is only half again its own size, so the shortfall came straight off the picture.
 *
 * <p>The oracle here is arithmetic rather than a stored frame: the picture is a known rectangle on a known plane, so for
 * every pixel it is decidable whether that ray should have landed on it. Every pixel that should be the picture is held
 * to being the picture, from several standing positions - the reported symptom appeared at some and not others.
 */
class CeilingSeesWallTest {

    private static final int SIZE = 64;

    /** Emissive, the way a wall's picture is drawn, so its pixels come back as exactly this. */
    private static final int PICTURE = 0xFF20C020;

    private static final int BLOCK = 0xFF808080;

    /** Where the wall picture's drawn face is, in blocks: the block face, plus the hair of proudness it keeps. */
    private static final double FACE = 1 + 1 / 1024.0;

    /**
     * A room with a ceiling at y = 5 and a wall at x = 1, meeting along that line.
     *
     * <p>The ceiling mirror is on the underside of the block at (1, 5, 2) and the wall mirror on the east face of the
     * block at (0, 4, 2), so the two are edge to edge and each is in the other's picture.
     */
    private static TestWorld room() {
        TestWorld world = new TestWorld()
                .texture("block", TestWorld.solid(BLOCK))
                .texture("picture", TestWorld.solid(PICTURE));

        for (int x = 0; x <= 5; x++) {
            for (int z = 0; z <= 5; z++) {
                world.cube(x, 5, z, "block", BakedState.Alpha.OPAQUE);
            }
        }
        for (int y = 1; y <= 4; y++) {
            for (int z = 0; z <= 5; z++) {
                world.cube(0, y, z, "block", BakedState.Alpha.OPAQUE);
            }
        }
        return world;
    }

    /** The wall mirror's picture: the middle of the block it hangs on, at the yaw a picture on an east face takes. */
    private static EntitySnapshot wallMirror() {
        return EntitySnapshot.wallMap(0.5, 4.5, 2.5, -270, "picture").emissive();
    }

    /**
     * The ceiling mirror's own frame for a viewer standing here: their eye reflected up through the ceiling, looking
     * straight down, clipped at the ceiling and windowed onto the ceiling glass.
     */
    private static CameraView reflection(double eyeX, double eyeY) {
        double depth = 5 - eyeY;
        double left = -(2 - eyeX) / depth;
        double right = -(1 - eyeX) / depth;

        return new CameraView(eyeX, 5 + depth, 2.5, 0, 90, 70, 32, false,
                new CameraView.ClipPlane(1.5, 5, 2.5, 0, -1, 0),
                CameraView.Lens.of(Math.min(left, right), Math.max(left, right), -0.5 / depth, 0.5 / depth), null);
    }

    /**
     * Every ray that should land on the wall mirror does.
     *
     * <p>Which pixels those are is solved rather than remembered: the picture is the square {@code y 4..5, z 2..3} on the
     * plane it is drawn at, so intersecting each pixel's own ray with that plane says whether it is on the picture.
     */
    @Test
    void everyRayThatShouldLandOnTheWallMirrorDoes() {
        TestWorld world = room();
        double[] direction = new double[3];

        for (double eyeX : new double[]{2.5, 3.5, 4.5, 5.5}) {
            CameraView view = reflection(eyeX, 1.6);
            int[] frame = new int[SIZE * SIZE];
            new RayCaster(world, Canopy.DEFAULT).render(world, view, List.of(wallMirror()), SIZE, SIZE, frame);

            CameraView.Frame rays = view.frame();
            int onThePicture = 0;
            int missing = 0;

            for (int py = 0; py < SIZE; py++) {
                for (int px = 0; px < SIZE; px++) {
                    rays.direction(px, py, SIZE, SIZE, direction);

                    double along = (FACE - view.x()) / direction[0];
                    if (along <= 0) continue;

                    double y = view.y() + direction[1] * along;
                    double z = view.z() + direction[2] * along;
                    if (y < 4 || y > 5 || z < 2 || z > 3) continue;

                    onThePicture++;
                    if (frame[py * SIZE + px] != PICTURE) {
                        missing++;
                    }
                }
            }

            assertTrue(onThePicture > SIZE * SIZE / 8, "from x=" + eyeX + " the wall mirror should be a real part of "
                    + "the reflection, and only " + onThePicture + " pixels of " + SIZE * SIZE + " were aimed at it");
            assertEquals(0, missing, "from x=" + eyeX + ", " + missing + " of " + onThePicture
                    + " pixels aimed at the wall mirror were drawn as something else - the missing band");
        }
    }
}
