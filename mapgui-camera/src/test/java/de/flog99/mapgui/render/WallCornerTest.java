package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two walls meeting at a corner, seen from a mirror on one of them.
 *
 * <p>Reported as a strip of block showing through: hang a mirror on each of two walls that meet, and each one shows the
 * other minus about a pixel along the edge they touch on - with the block the other mirror hangs on visible in the gap.
 *
 * <p>The cause is depth rather than width. A wall's picture is drawn a little proud of the block face so that it does not
 * fight whatever the block is made of, and a pixel of proudness is invisible looking at it - but at a corner the
 * displacement is <b>sideways</b>. The other wall's picture no longer reaches the corner line, and a ray aimed into the
 * corner passes in front of it and lands on the block behind instead.
 *
 * <p>{@link WallLayout} is the authority on where a wall is and it says the block face - which is also the plane a
 * mirror clips its frame at. So the picture belongs on the face, with only enough offset to settle which of the two
 * coincident surfaces is in front.
 *
 * <p>The geometry here is the reported one. The room is {@code x > 1, z > 1}; one wall faces east with a mirror on the
 * block at {@code z = 1}, the other faces south with a mirror on the block at {@code x = 1}, and they share the vertical
 * line at {@code x = 1, z = 1}. The frame is the east mirror's own: an eye reflected two blocks back of its glass,
 * clipped at it, with a window fitted to it, so column zero is the corner.
 */
class WallCornerTest {

    private static final int SIZE = 128;

    /**
     * The other mirror's picture, and the block it hangs on.
     *
     * <p>The picture is drawn <b>emissive</b>, the way {@code WallCapture} draws one, so its pixels come back as exactly
     * this colour. The block's do not - they are shaded by the face they are on, which is worth saying because comparing
     * against the unshaded value is how the first version of this test passed while the strip was still there.
     */
    private static final int PICTURE = 0xFF20C020;
    private static final int BLOCK = 0xFF808080;

    /** Two blocks back from the glass, which is where a viewer two blocks in front puts the camera. */
    private static final double DEPTH = 2;

    private static TestWorld corner() {
        TestWorld world = new TestWorld()
                .texture("block", TestWorld.solid(BLOCK))
                .texture("picture", TestWorld.solid(PICTURE));

        // The two wall blocks the mirrors hang on, and their neighbours, so the walls read as walls.
        for (int along = 0; along <= 4; along++) {
            for (int y = 0; y <= 3; y++) {
                world.cube(0, y, 1 + along, "block", BakedState.Alpha.OPAQUE);
                world.cube(1 + along, y, 0, "block", BakedState.Alpha.OPAQUE);
            }
        }
        return world;
    }

    /**
     * The picture on the south-facing wall, on the block at {@code x = 1}: a full block face, pointing +z.
     *
     * <p>Placed the way {@code WallCapture} places one - the middle of the block it hangs on, and the yaw a hung
     * picture takes for that face - and emissive, so the pixel that comes back is the texture's own colour.
     */
    private static EntitySnapshot theOtherMirror(int y) {
        return EntitySnapshot.wallMap(1.5, y + 0.5, 0.5, -180, "picture").emissive();
    }

    /** The east-facing mirror's own frame: clipped at its glass, and windowed onto exactly the glass. */
    private static CameraView reflection(int y) {
        double tangent = 0.5 / DEPTH;
        return new CameraView(1 - DEPTH, y + 0.5, 1.5, -90, 0, 70, 32, false,
                new CameraView.ClipPlane(1, y + 0.5, 1.5, 1, 0, 0),
                CameraView.Lens.of(-tangent, tangent, -tangent, tangent), null);
    }

    private static int[] rendered(TestWorld world, int y) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world, Canopy.DEFAULT).render(world, reflection(y), List.of(theOtherMirror(y)), SIZE, SIZE, out);
        return out;
    }

    /**
     * The column of the reflection that looks into the corner shows the other mirror, not what it hangs on.
     *
     * <p>Column zero is the edge of the glass that touches the other wall, and every row of it is aimed a hair past the
     * corner line, where there is nothing but the other mirror's own face. Before this it was the block behind it, all
     * 128 rows of it - the strip this test is named after.
     */
    @Test
    void theCornerColumnShowsTheOtherMirrorRatherThanTheBlockBehindIt() {
        int[] frame = rendered(corner(), 1);

        for (int row = 0; row < SIZE; row++) {
            assertEquals(PICTURE, frame[row * SIZE],
                    "the corner column showed something other than the other mirror, at row " + row);
        }
    }

    /**
     * And this scene really can draw that block, or the test above passes for the wrong reason.
     *
     * <p>It is a wall four blocks long and the picture covers one block of it, so most of what the reflection sees along
     * that wall <b>is</b> the block - which is what makes the corner column worth an assertion of its own.
     */
    @Test
    void theBlockIsDrawnEverywhereElseAlongThatWall() {
        int[] frame = rendered(corner(), 1);

        int block = 0;
        int picture = 0;
        for (int pixel : frame) {
            if (pixel == PICTURE) {
                picture++;
            } else if (pixel != TestWorld.SKY) {
                block++;
            }
        }

        assertTrue(picture > SIZE * SIZE / 16, "the other mirror should be a real part of the reflection, and "
                + picture + " pixels of " + SIZE * SIZE + " were");
        assertTrue(block > SIZE * SIZE / 16, "the block should be drawn where the picture does not cover it, and "
                + block + " pixels were");
    }
}
