package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A capture that is not square, and the one property that makes it worth having.
 *
 * <p><b>A slice of a wide frame is the frame that slice would have been on its own.</b> Which is what lets several
 * mirrors on one wall be photographed together: their camera is the same point - a reflection looks out along the plane's
 * normal, so the eye depends on the wall and the viewer and not on which mirror - and each mirror is then a window onto
 * that one picture. Cut them apart and every one of them has to be pixel for pixel what it would have got alone, or
 * sharing a frame would be a quiet loss of quality rather than a saving.
 *
 * <p>Why it matters that it is <i>exact</i>: a row of mirrors down a wall costs one frame instead of one each, so they
 * all show the same instant instead of updating one after another - and the pixels each of them reads have to be the
 * ones it would have traced for itself, or "shared" would mean "resampled".
 *
 * <p>The windows here are whole numbers and halves on purpose. The two ways of arriving at the middle of a span -
 * halving it, or stepping to it a pixel at a time - are the same number in exact arithmetic and need not be in floating
 * point, so a test that wants <i>identical</i> has to pick a span where they are.
 */
class WideFrameTest {

    private static final int TALL = 32;
    private static final BakedState.Alpha OPAQUE = BakedState.Alpha.OPAQUE;

    /** Two colours a step apart, so a frame drawn half a pixel out reads differently rather than merely dimmer. */
    private static final int LEFT_WALL = 0xFF2060C0;
    private static final int RIGHT_WALL = 0xFFC06020;

    /** A hung picture's yaw for one whose front looks back down +Z, which is where this camera is. */
    private static final float FACING_THE_CAMERA = 180;

    /** The face of the block at z=0 that looks toward -Z, which is where a mirror on it would be. */
    private static final CameraView.ClipPlane GLASS = new CameraView.ClipPlane(0.5, 2, 0, 0, 0, -1);

    /**
     * A room with a different colour on each side of the middle, at the depth a reflection finds it.
     *
     * <p>Split down the middle so that a frame taken as one wide picture and the same frame taken in halves cannot agree
     * by accident: a mistake in where a pixel looks moves the seam between the two colours.
     */
    private static TestWorld room() {
        TestWorld world = new TestWorld()
                .texture("left", TestWorld.solid(LEFT_WALL))
                .texture("right", TestWorld.solid(RIGHT_WALL));

        for (int x = -24; x <= 24; x++) {
            for (int y = -8; y <= 12; y++) {
                world.cube(x, y, -6, x < 0 ? "left" : "right", OPAQUE);
            }
        }
        return world;
    }

    /** The reflected eye, three blocks back of the glass and looking out along its normal. */
    private static CameraView through(CameraView.Lens window, int width, int height) {
        return new CameraView(0.5, 2, 3, 180, 0, CameraView.DEFAULT_FOV, 64, false, GLASS, window, null);
    }

    private static int[] rendered(TestWorld world, CameraView view, int width, int height) {
        return rendered(world, view, width, height, List.of());
    }

    private static int[] rendered(TestWorld world, CameraView view, int width, int height, List<EntitySnapshot> in) {
        int[] out = new int[width * height];
        new RayCaster(world).render(world, view, in, width, height, out);
        return out;
    }

    /** One column range of a frame, so a slice of the wide one can be held against a frame of its own. */
    private static int[] columns(int[] frame, int width, int height, int from, int wide) {
        int[] out = new int[wide * height];
        for (int py = 0; py < height; py++) {
            System.arraycopy(frame, py * width + from, out, py * wide, wide);
        }
        return out;
    }

    /**
     * The left half of a wide frame is the frame that half would have been on its own, pixel for pixel.
     *
     * <p>This is the whole of sharing one capture between several mirrors.
     */
    @Test
    void eachHalfOfAWideFrameIsTheFrameThatHalfWouldHaveBeenAlone() {
        TestWorld world = room();

        // Two blocks across and one down, as tangents: a span of 2 halves exactly, and so does a pixel of it.
        CameraView.Lens whole = CameraView.Lens.of(-1, 1, -0.5, 0.5);
        CameraView.Lens leftHalf = CameraView.Lens.of(-1, 0, -0.5, 0.5);
        CameraView.Lens rightHalf = CameraView.Lens.of(0, 1, -0.5, 0.5);

        int[] wide = rendered(world, through(whole, TALL * 2, TALL), TALL * 2, TALL);
        int[] left = rendered(world, through(leftHalf, TALL, TALL), TALL, TALL);
        int[] right = rendered(world, through(rightHalf, TALL, TALL), TALL, TALL);

        assertArrayEquals(left, columns(wide, TALL * 2, TALL, 0, TALL),
                "the left half of a wide frame is not what the left half would have drawn on its own");
        assertArrayEquals(right, columns(wide, TALL * 2, TALL, TALL, TALL),
                "and the right half is not either");
    }

    /**
     * The same, with something standing in the room - because an entity is projected rather than traced per pixel.
     *
     * <p>Blocks and entities take different paths through a frame. A block is found by walking the ray, which is the same
     * arithmetic whatever shape the frame is. An entity is put on the screen first, by {@link EntityScreen}, and only the
     * pixels its rect covers ever test it - so a rect worked out as if the frame were square would clip a mob, or a
     * <b>map on a wall</b>, down one side of a wide one.
     *
     * <p>Which is exactly the case a mirror runs into: another mirror in the reflection is a wall map, drawn as a quad
     * with that wall's pixels on it, and that is what makes a mirror facing a mirror recurse at all.
     */
    @Test
    void aWideFramePutsEntitiesWhereTheNarrowOneDoes() {
        TestWorld world = room().texture("picture", TestWorld.solid(0xFF20C020));

        // A map hung on the far wall, off to one side, so it lands in one half of the wide frame and near the edge of
        // its own narrow one - which is where a rect that is a factor out shows.
        List<EntitySnapshot> hung = List.of(
                EntitySnapshot.wallMap(-1.5, 2, -5.4, 0, "picture"),
                EntitySnapshot.box(2.5, 1, -4, 0, 0, 0.9, 1.4, "picture"));

        CameraView.Lens whole = CameraView.Lens.of(-1, 1, -0.5, 0.5);
        int[] wide = rendered(world, through(whole, TALL * 2, TALL), TALL * 2, TALL, hung);

        for (int half = 0; half < 2; half++) {
            CameraView.Lens part = half == 0
                    ? CameraView.Lens.of(-1, 0, -0.5, 0.5)
                    : CameraView.Lens.of(0, 1, -0.5, 0.5);
            int[] alone = rendered(world, through(part, TALL, TALL), TALL, TALL, hung);

            assertArrayEquals(alone, columns(wide, TALL * 2, TALL, half * TALL, TALL),
                    "half " + half + " of a wide frame drew its entities differently from the frame it would have been");
        }
    }

    /**
     * A wall's picture comes out at its own brightness, not dimmed by the wall it hangs on.
     *
     * <p>The bug this pins is a compounding one, so one pass hides it. A wall map used to be shaded like matter - by the
     * light where it stands and by the <b>face shade</b> of its facing, 0.6 on an east or west wall - and a mirror facing
     * a mirror photographs the other's map every frame. So the tunnel went 0.6, 0.36, 0.22 and was black by the fourth
     * level, turning red on the way down: the darkest colours a map has are a warm TERRACOTTA_BLACK and a neutral
     * COLOR_BLACK, so anything marching toward black lands on the warm one.
     *
     * <p>Its pixels are a finished picture - a capture, a video frame, a menu - so the light is already in them. Anything
     * this test allows to multiply them once will multiply them again per level.
     */
    @Test
    void aWallsPictureIsDrawnAtItsOwnBrightness() {
        int green = 0xFF20C020;
        TestWorld world = room().texture("picture", TestWorld.solid(green));

        // Square on, a block in front of the far wall, so the ray meets the picture's own face.
        List<EntitySnapshot> hung = List.of(
                EntitySnapshot.wallMap(0.5, 2, -5, FACING_THE_CAMERA, "picture").emissive());

        int[] frame = rendered(world, through(CameraView.Lens.of(-0.2, 0.2, -0.2, 0.2), TALL, TALL), TALL, TALL, hung);

        int middle = frame[TALL / 2 * TALL + TALL / 2];
        assertEquals(green & 0xFFFFFF, middle & 0xFFFFFF,
                "a wall's picture should come out exactly as it was drawn, but came out "
                        + Integer.toHexString(middle));
    }

    /** And the same picture as matter is dimmed, which is what everything else in a capture wants. */
    @Test
    void anythingMadeOfMatterIsStillShaded() {
        int green = 0xFF20C020;
        TestWorld world = room().texture("picture", TestWorld.solid(green));

        List<EntitySnapshot> hung = List.of(EntitySnapshot.wallMap(0.5, 2, -5, FACING_THE_CAMERA, "picture"));
        int[] frame = rendered(world, through(CameraView.Lens.of(-0.2, 0.2, -0.2, 0.2), TALL, TALL), TALL, TALL, hung);

        int middle = frame[TALL / 2 * TALL + TALL / 2];
        int drawnGreen = middle >> 8 & 0xFF;

        // Darker rather than merely different, which is what says the ray met the picture at all: a ray that missed it
        // would land on the wall behind and pass a test that only asked for "not the same colour".
        assertTrue(drawnGreen > 0 && drawnGreen < (green >> 8 & 0xFF),
                "a picture made of matter should be dimmed by where it hangs, but came out "
                        + Integer.toHexString(middle));
    }

    /** And the halves really are different pictures, or the test above would pass on a frame of one flat colour. */
    @Test
    void theTwoHalvesAreNotTheSamePicture() {
        int[] wide = rendered(room(), through(CameraView.Lens.of(-1, 1, -0.5, 0.5), TALL * 2, TALL), TALL * 2, TALL);

        int leftPixel = wide[TALL / 2 * TALL * 2 + TALL / 2];
        int rightPixel = wide[TALL / 2 * TALL * 2 + TALL + TALL / 2];

        assertTrue(leftPixel != rightPixel,
                "the room was built with a different colour each side, so the halves must differ");
    }

    /**
     * A wide frame's pixels are square, which is what stops the picture being stretched.
     *
     * <p>Read off the geometry rather than trusted: the direction a pixel looks steps by the same tangent across as it
     * does down, when the window and the pixel count have the same proportions.
     */
    @Test
    void aWideFrameHasSquarePixels() {
        CameraView view = through(CameraView.Lens.of(-1, 1, -0.5, 0.5), TALL * 2, TALL);
        CameraView.Frame frame = view.frame();

        double[] first = new double[3];
        double[] alongOne = new double[3];
        double[] downOne = new double[3];
        frame.direction(TALL, TALL / 2, TALL * 2, TALL, first);
        frame.direction(TALL + 1, TALL / 2, TALL * 2, TALL, alongOne);
        frame.direction(TALL, TALL / 2 + 1, TALL * 2, TALL, downOne);

        double across = Math.hypot(alongOne[0] - first[0], alongOne[2] - first[2]);
        double down = Math.abs(downOne[1] - first[1]);

        assertEquals(across, down, across * 0.02, "a pixel should step as far across as it does down");
    }
}
