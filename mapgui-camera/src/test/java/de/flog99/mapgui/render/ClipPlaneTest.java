package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A camera behind a wall, and the plane that lets it see past one.
 *
 * <p>This exists for reflections. A mirror's camera sits as far behind the glass as the viewer is in front of it, so
 * without a near plane the first thing every single ray meets is the inside of the wall the mirror hangs on - a
 * reflection of solid stone. The test is therefore the smallest statement of the whole feature: the same eye, the same
 * direction, unclipped draws the wall and clipped draws the room on the other side of it.
 *
 * <p>The other half is that it costs nothing when nobody asks. An unclipped view has to come out byte-identical to a
 * view with no clip field at all, because every capture out of a player's own head is one.
 */
class ClipPlaneTest {

    private static final int SIZE = 32;
    private static final BakedState.Alpha OPAQUE = BakedState.Alpha.OPAQUE;

    private static final int WALL = 0xFF808080;
    private static final int BEYOND = 0xFFE02020;

    /** A block right against the glass, in a colour neither of the others could be mistaken for. */
    private static final int NEAR = 0xFF20E020;

    /** The face of the block at z=0 that looks toward -Z, which is where a mirror hung on it would be. */
    private static final CameraView.ClipPlane GLASS = new CameraView.ClipPlane(0.5, 2, 0, 0, 0, -1);

    /**
     * A wall across z=0 with something unmistakable behind it, at the depth a reflection would find it.
     *
     * <p>Wide enough that no ray of a 70 degree frame slips round the edge, which would pass this test for the wrong
     * reason.
     */
    private static TestWorld room() {
        TestWorld world = new TestWorld()
                .texture("wall", TestWorld.solid(WALL))
                .texture("beyond", TestWorld.solid(BEYOND));

        for (int x = -8; x <= 8; x++) {
            for (int y = -4; y <= 8; y++) {
                world.cube(x, y, 0, "wall", OPAQUE);
                // The far side of the room, so a clipped ray ends on something rather than on the sky.
                world.cube(x, y, -6, "beyond", OPAQUE);
            }
        }
        return world;
    }

    /** Where a mirror on that wall would put its camera: reflected through the glass, looking back at it. */
    private static CameraView reflected(CameraView.ClipPlane clip) {
        return new CameraView(0.5, 2, 3, 180, 0, CameraView.DEFAULT_FOV, 64, false, clip);
    }

    private static int[] rendered(TestWorld world, CameraView view, List<EntitySnapshot> entities) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world).render(world, view, entities, SIZE, SIZE, out);
        return out;
    }

    private static int centre(int[] frame) {
        return frame[SIZE / 2 * SIZE + SIZE / 2];
    }

    /** Whether every pixel of a frame came out as one colour, which is what a wall filling the view looks like. */
    private static boolean allOf(int[] frame, int argb) {
        for (int pixel : frame) {
            if (pixel != argb) return false;
        }
        return true;
    }

    /**
     * A block pressed against the glass is in the reflection.
     *
     * <p>The one block a walk skips is the one it starts in - standing inside a block should not paint that block's inside
     * over the whole frame - and for a clipped frame the start is not where the camera is. It is the crossing, a point in
     * the middle of the room, and the block it lands in is the block directly in front of the mirror. Skipping that hid
     * everything within a block of the glass: a chest against a mirror, a flower on the shelf under it, the snow on the
     * floor beneath one on the wall.
     */
    @Test
    void aBlockAgainstTheGlassIsDrawn() {
        TestWorld world = room().texture("near", TestWorld.solid(NEAR));
        // The blocks the crossing lands in: the glass is the z=0 plane looking toward -Z, so this layer is right against
        // it. A patch rather than one block, since the eye sits on a block boundary and the middle ray leaves a lone one
        // through its edge before it has gone anywhere.
        for (int x = -1; x <= 1; x++) {
            for (int y = 1; y <= 3; y++) {
                world.cube(x, y, -1, "near", OPAQUE);
            }
        }

        int drawn = centre(rendered(world, reflected(GLASS), List.of()));

        // By which channel came out on top rather than by the exact value, since whatever is drawn is shaded by its face
        // and its light - and those have their own tests. The three colours in this room are a green, a red and a grey.
        assertTrue(greenest(drawn), "a block against the glass should be the nearest thing in the reflection, but the "
                + "frame drew " + Integer.toHexString(drawn));
    }

    /** Whether a pixel is the near block's green rather than the far wall's red or the wall's grey. */
    private static boolean greenest(int argb) {
        int red = argb >> 16 & 0xFF;
        int green = argb >> 8 & 0xFF;
        int blue = argb & 0xFF;

        return green > red * 2 && green > blue * 2;
    }

    /**
     * And its face is drawn even though a solid block is against it, because that block is behind the plane.
     *
     * <p>The second half of the same bug and the half that bites in a real world, where every block model drops the faces
     * a neighbour would hide. The face of a chest that touches a mirror is hidden by the wall the mirror hangs on - so it
     * was dropped, and the reflection showed straight through the chest to whatever was beyond it. A wall behind the glass
     * is exactly what a reflection does not draw, so it hides nothing in one.
     */
    @Test
    void aFaceAgainstTheGlassIsNotCulledByTheWallBehindIt() {
        TestWorld world = room().texture("near", TestWorld.solid(NEAR));
        for (int x = -1; x <= 1; x++) {
            for (int y = 1; y <= 3; y++) {
                world.culledCube(x, y, -1, "near");
            }
        }

        int drawn = centre(rendered(world, reflected(GLASS), List.of()));

        assertTrue(greenest(drawn), "the face touching the glass should still be drawn, but the frame drew "
                + Integer.toHexString(drawn));
    }

    @Test
    void unclippedDrawsTheWallItStartsInside() {
        int[] frame = rendered(room(), reflected(null), List.of());

        // Not a claim about shading, only that nothing but the wall is in it - which is the bug the clip exists for.
        assertNotEquals(BEYOND, centre(frame), "the far side of the room cannot be visible through a wall");
        assertNotEquals(TestWorld.SKY, centre(frame), "a camera buried in stone is not looking at the sky");
    }

    @Test
    void clippedAtTheGlassDrawsTheRoomInFront() {
        int[] frame = rendered(room(), reflected(GLASS), List.of());

        assertTrue(allOf(frame, shadedBeyond(frame)), "every ray should have crossed the glass and landed beyond it");
        assertEquals(shadedBeyond(frame), centre(frame));
        assertNotEquals(WALL, centre(frame), "the wall the camera started behind must not be in the frame");
    }

    /**
     * The colour the far wall actually came out as, read off the frame rather than asserted.
     *
     * <p>What matters here is the geometry, and pinning the exact shade would tie this test to the light curve - which
     * has its own tests and is free to move.
     */
    private static int shadedBeyond(int[] frame) {
        return centre(frame);
    }

    @Test
    void aClipCostsNothingWhenThereIsNone() {
        TestWorld world = room();
        CameraView open = new CameraView(0.5, 2, -3, 180, 0, CameraView.DEFAULT_FOV, 64);
        CameraView spelledOut = new CameraView(0.5, 2, -3, 180, 0, CameraView.DEFAULT_FOV, 64, false, null);

        assertArrayEquals(rendered(world, open, List.of()), rendered(world, spelledOut, List.of()),
                "a null clip has to be the same frame the shorter constructor always drew");
    }

    /**
     * A frame whose rays cannot reach the half-space at all draws the backdrop rather than walking the world.
     *
     * <p>Turned right round, away from the glass. Every ray of the frame heads further behind the plane, so none of
     * them has a crossing ahead of it - and a walk started from infinity would read blocks at coordinates that are
     * not numbers. Note it has to be the whole frame rather than the middle ray: a 70 degree fan looking <i>along</i>
     * the plane still sends half its rays through it.
     */
    @Test
    void raysThatNeverCrossDrawNothing() {
        TestWorld world = room();
        // Yaw 0 faces +Z, directly away from a mirror whose front is -Z.
        CameraView backToIt = new CameraView(0.5, 2, 3, 0, 0, CameraView.DEFAULT_FOV, 64, false, GLASS);

        int before = world.reads();
        int[] frame = rendered(world, backToIt, List.of());

        assertEquals(before, world.reads(), "nothing should have been looked up for a frame that sees nothing");
        assertTrue(allOf(frame, TestWorld.SKY), "a frame with no half-space in it is the backdrop and nothing else");
    }

    @Test
    void entryIsZeroFromInsideTheHalfSpace() {
        // In front of the glass already, looking anywhere: there is no crossing ahead to skip to.
        assertEquals(0, GLASS.entry(0.5, 2, -3, 0, 0, -1));
        assertEquals(0, GLASS.entry(0.5, 2, -3, 0, 0, 1));
    }

    @Test
    void entryIsTheCrossingFromBehindIt() {
        // Three blocks behind, heading straight at it, so the crossing is three away give or take the skin.
        assertEquals(3, GLASS.entry(0.5, 2, 3, 0, 0, -1), 1e-3);

        // Off axis at 45 degrees, where the distance along the ray is the perpendicular one over cos 45.
        double diagonal = 1 / Math.sqrt(2);
        assertEquals(3 * Math.sqrt(2), GLASS.entry(0.5, 2, 3, diagonal, 0, -diagonal), 1e-3);
    }

    @Test
    void entryIsInfiniteForARayThatRunsParallelBehindIt() {
        assertTrue(Double.isInfinite(GLASS.entry(0.5, 2, 3, 1, 0, 0)));
        assertTrue(Double.isInfinite(GLASS.entry(0.5, 2, 3, 0, 1, 0)));
    }

    /**
     * And infinite for one heading further behind, which is the case that reads as a crossing if you only solve the
     * line against the plane: there is an intersection, it is simply behind the eye. Taking it would start the walk
     * back there and point it at the wall.
     */
    @Test
    void entryIsInfiniteForARayHeadingAwayFromTheGlass() {
        assertTrue(Double.isInfinite(GLASS.entry(0.5, 2, 3, 0, 0, 1)));

        double diagonal = 1 / Math.sqrt(2);
        assertTrue(Double.isInfinite(GLASS.entry(0.5, 2, 3, diagonal, 0, diagonal)));
    }

    @Test
    void entryStartsTheRayInFrontRatherThanOnThePlane() {
        double at = GLASS.entry(0.5, 2, 3, 0, 0, -1);

        // A ray that started exactly on the glass would be free to hit the very surface it started from, which on a
        // mirror is the block the mirror hangs on and so the whole frame.
        assertTrue(at > 3, "the crossing should be nudged past the plane, not left on it");
        assertTrue(GLASS.keeps(0.5, 2, 3 - at), "and the nudge has to land on the side that is kept");
    }

    @Test
    void keepsAnswersWhichSideOfTheGlassSomethingIsOn() {
        assertTrue(GLASS.keeps(0.5, 2, -1), "in front of the mirror, so in the reflection");
        assertFalse(GLASS.keeps(0.5, 2, 1), "behind it, so not");
    }

    /**
     * A masked frame draws the pixels it was asked for and leaves the rest exactly as they were.
     *
     * <p>Two halves, and the second is the one worth a test. Skipping is easy; skipping and still drawing every wanted
     * pixel <b>identically</b> to an unmasked frame is the promise - a mask that changed what the kept pixels look like
     * would be a mask that cannot be trusted to be free, and the symptom would be a seam at the edge of the shape.
     */
    @Test
    void aMaskDrawsWhatItAsksForAndNothingElse() {
        TestWorld world = room();
        CameraView open = reflected(GLASS);

        int[] everything = rendered(world, open, List.of());

        // A disc, the way a round mirror's glass is one.
        boolean[] wanted = new boolean[SIZE * SIZE];
        double middle = SIZE / 2.0;
        for (int py = 0; py < SIZE; py++) {
            for (int px = 0; px < SIZE; px++) {
                double dx = px + 0.5 - middle;
                double dy = py + 0.5 - middle;
                wanted[py * SIZE + px] = dx * dx + dy * dy <= middle * middle;
            }
        }

        CameraView masked = new CameraView(open.x(), open.y(), open.z(), open.yaw(), open.pitch(), open.fov(),
                open.maxDistance(), open.fog(), open.clip(), open.window(), wanted);

        int before = world.lit();
        int[] partial = rendered(world, masked, List.of());
        int maskedShading = world.lit() - before;

        for (int at = 0; at < everything.length; at++) {
            if (wanted[at]) {
                assertEquals(everything[at], partial[at], "a wanted pixel came out differently under a mask, at " + at);
            } else {
                assertEquals(0, partial[at], "an unwanted pixel was drawn anyway, at " + at);
            }
        }

        // And it really skipped the work rather than drawing and discarding: a disc is pi over four of its square, so it
        // shades about a fifth less. Counted in shading rather than in block reads, because a frame remembers the blocks
        // it has already looked at - see SeenBlocks - so two frames over one room read nearly the same positions however
        // many rays each of them cast. Light is asked for per texel drawn and is not remembered, so it still counts rays.
        before = world.lit();
        rendered(world, open, List.of());
        int wholeShading = world.lit() - before;

        assertTrue(maskedShading < wholeShading * 0.85,
                "a disc should shade about a fifth less of the frame, but shaded " + maskedShading
                        + " of " + wholeShading);
    }

    /** No mask is every pixel, which is what keeps it free for the captures that do not want one. */
    @Test
    void noMaskWantsEverything() {
        CameraView open = reflected(GLASS);

        assertTrue(open.wants(0, 0, SIZE));
        assertTrue(open.wants(SIZE - 1, SIZE - 1, SIZE));
    }
}
