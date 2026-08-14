package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RayCasterTest {

    private static final int WHITE = 0xFFFFFFFF;

    /** One pixel looking exactly where the camera looks, which is the whole frame at 1x1. */
    private int pixel(TestWorld world, CameraView view) {
        int[] out = new int[1];
        new RayCaster(world).render(world, view, 1, 1, out);
        return out[0];
    }

    private CameraView at(double x, double y, double z, float yaw, float pitch) {
        return new CameraView(x, y, z, yaw, pitch, CameraView.DEFAULT_FOV, 64);
    }

    /**
     * What a white texture comes out as at full light on a face with the given direction factor.
     *
     * <p>Full light is not 1, and that is not a rounding slip: the client's own table finishes by pulling every entry
     * four percent toward grey, at level 15 as much as in a cave, which leaves 0.99. The shadow lift then adds almost
     * nothing here on purpose, being weighted at the dark end.
     *
     * <p>Read off the renderer's own table rather than re-derived here. Deriving it meant two copies of the lift
     * arithmetic, and the copy in this file went stale the first time the weighting was retuned.
     */
    private static int shaded(double faceFactor) {
        return (int) (255 * faceFactor * RayCaster.lightTable(0)[15]);
    }

    /**
     * A blockstate {@code y} rotation turns the texture with the block, not just the box.
     *
     * <p>The bug this pins was worth a whole afternoon of bed-staring. The geometry is turned at bake time and the
     * face rectangles are not, so a hit has to be carried back into the space the uv was written in - and that was
     * done by turning back {@code 4 - n} times with the inverse quarter instead of turning on {@code 4 - n} times
     * with the same one. Inverse cubed is forward, so 0 and 180 came out perfect and 90 and 270 were half a circle
     * out: north-south beds looked right, east-west beds had their pillow at the foot, and doors read as mirrored.
     *
     * <p>Asserted as a relation rather than as a colour I picked: the corner of the texture that faces north-west
     * unrotated has to face north-east after a quarter turn, because that is what turning a block does.
     */
    @Test
    void aBlockstateYRotationTurnsTheTextureWithTheBlock() {
        int northWest = 0xFFFF0000;
        int northEast = 0xFF00FF00;
        int southWest = 0xFF0000FF;
        int southEast = 0xFFFFFF00;

        // On an up face the texture's v runs along +z, so its top edge is the block's north side.
        TestWorld straight = new TestWorld()
                .texture("quads", TestWorld.quadrants(northWest, northEast, southWest, southEast))
                .turnedCube(0, 0, 0, "quads", 0);
        TestWorld turned = new TestWorld()
                .texture("quads", TestWorld.quadrants(northWest, northEast, southWest, southEast))
                .turnedCube(0, 0, 0, "quads", 90);

        // Straight down onto the north-west quarter of the top, and onto the north-east quarter.
        int unrotatedNorthWest = pixel(straight, at(0.25, 6.5, 0.25, 0, 90));
        int rotatedNorthEast = pixel(turned, at(0.75, 6.5, 0.25, 0, 90));

        assertEquals(unrotatedNorthWest, rotatedNorthEast,
                "a quarter turn carries the north-west corner of the texture round to the north-east");
        assertNotEquals(unrotatedNorthWest, pixel(turned, at(0.25, 6.5, 0.75, 0, 90)),
                "and not to the south-west, which is where a half circle of error would have put it");
    }

    private static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    // --- nothing there ---

    @Test
    void anEmptyWorldIsAllSky() {
        TestWorld world = new TestWorld();

        assertEquals(TestWorld.SKY, pixel(world, at(0.5, 0.5, 0.5, 0, 0)));
    }

    @Test
    void aRayBeyondTheDistanceCapSeesSky() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 40, "white", BakedState.Alpha.OPAQUE);

        CameraView near = new CameraView(0.5, 0.5, 0.5, 0, 0, CameraView.DEFAULT_FOV, 8);
        assertEquals(TestWorld.SKY, pixel(world, near), "a block 40 away is past a cap of 8");

        CameraView far = new CameraView(0.5, 0.5, 0.5, 0, 0, CameraView.DEFAULT_FOV, 64);
        assertNotEquals(TestWorld.SKY, pixel(world, far));
    }

    /** Standing inside a block should not paint the inside of it over everything. */
    @Test
    void theCamerasOwnBlockIsSkipped() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 0, "white", BakedState.Alpha.OPAQUE);

        assertEquals(TestWorld.SKY, pixel(world, at(0.5, 0.5, 0.5, 0, 0)));
    }

    // --- which face was hit ---

    /**
     * Face shading is the only lighting there is, so these multipliers are visible in every frame: up is full,
     * the north-south pair is 0.8, east-west 0.6 and down 0.5. A white texture at full light lands exactly on
     * them, which makes this the test for the walk reporting the right face.
     */
    @Test
    void theFaceEnteredDecidesTheShading() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE));
        world.cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(0.8), red(pixel(world, at(0.5, 0.5, 0.5, 0, 0))), "looking south hits a north face, 0.8");

        TestWorld fromSouth = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 0, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(0.8), red(pixel(fromSouth, at(0.5, 0.5, 5.5, 180, 0))), "looking north hits a south face, also 0.8");

        TestWorld below = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 0, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(1.0), red(pixel(below, at(0.5, 6.5, 0.5, 0, 90))), "looking down hits an up face, 1.0");

        TestWorld above = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 6, 0, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(0.5), red(pixel(above, at(0.5, 0.5, 0.5, 0, -90))), "looking up hits a down face, 0.5");

        TestWorld east = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(5, 0, 0, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(0.6), red(pixel(east, at(0.5, 0.5, 0.5, 270, 0))), "looking east hits a west face, 0.6");

        TestWorld west = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 0, "white", BakedState.Alpha.OPAQUE);
        assertEquals(shaded(0.6), red(pixel(west, at(5.5, 0.5, 0.5, 90, 0))), "looking west hits an east face, 0.6");
    }

    // --- light and tint ---

    @Test
    void darknessDarkensButDoesNotBlacken() {
        TestWorld lit = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);
        TestWorld dark = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE).defaultLight(0);

        int inLight = red(pixel(lit, at(0.5, 0.5, 0.5, 0, 0)));
        int inDark = red(pixel(dark, at(0.5, 0.5, 0.5, 0, 0)));

        assertTrue(inDark < inLight, "dark should be darker");
        assertTrue(inDark > 0, "but a cave should read as gloom rather than as a hole in the picture");
    }

    /** Light is read from the air the ray came through, since the light inside a solid block is zero. */
    @Test
    void lightComesFromTheAirInFrontOfTheFace() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE))
                .cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE)
                .defaultLight(0)
                .light(0, 0, 4, 15);

        assertEquals(shaded(0.8), red(pixel(world, at(0.5, 0.5, 0.5, 0, 0))), "the lit air in front, not the dark block itself");
    }

    /** grass_block_top is flat grey on disk. Untinted it renders as concrete. */
    @Test
    void aTintedFaceIsMultipliedByTheWorldsColor() {
        TestWorld world = new TestWorld().texture("grey", TestWorld.solid(0xFF808080))
                .cube(0, 0, 5, "grey", BakedState.Alpha.OPAQUE, 0)
                .tint(0, 0xFF40FF40);

        int argb = pixel(world, at(0.5, 0.5, 0.5, 0, 0));

        assertTrue((argb >> 8 & 0xFF) > red(argb), "a green tint should leave more green than red");
    }

    // --- transparency ---

    @Test
    void aTranslucentBlockBlendsWithWhatIsBehindIt() {
        TestWorld world = new TestWorld()
                .texture("glass", TestWorld.solid(0x80FF0000))
                .texture("white", TestWorld.solid(WHITE))
                .cube(0, 0, 3, "glass", BakedState.Alpha.TRANSLUCENT)
                .cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);

        int blended = pixel(world, at(0.5, 0.5, 0.5, 0, 0));

        TestWorld glassOnly = new TestWorld().texture("glass", TestWorld.solid(0x80FF0000)).cube(0, 0, 3, "glass", BakedState.Alpha.TRANSLUCENT);
        TestWorld wallOnly = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);

        assertNotEquals(blended, pixel(glassOnly, at(0.5, 0.5, 0.5, 0, 0)), "the wall behind has to show through");
        assertNotEquals(blended, pixel(wallOnly, at(0.5, 0.5, 0.5, 0, 0)), "and the glass in front has to tint it");
        assertTrue(red(blended) > 128, "red glass over a white wall should read red");
    }

    /**
     * A cutout is transparent only where its texture is. This is what makes leaves work, and it is decided per
     * texel rather than per block.
     */
    @Test
    void aRayPassesThroughTheClearPartOfACutout() {
        TestWorld world = new TestWorld()
                .texture("leaves", TestWorld.halfClear(0xFF00FF00))
                .texture("white", TestWorld.solid(WHITE))
                .cube(0, 0, 3, "leaves", BakedState.Alpha.CUTOUT)
                .cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);

        // The clear half is u < 8, and on a north face u runs 16-x, so the clear half is the high x side.
        int throughGap = pixel(world, at(0.9, 0.5, 0.5, 0, 0));
        int throughLeaf = pixel(world, at(0.1, 0.5, 0.5, 0, 0));

        assertEquals(shaded(0.8), red(throughGap), "the gap should show the white wall behind");
        assertTrue((throughLeaf >> 8 & 0xFF) > red(throughLeaf), "the leaf itself should be green");
    }

    /** A run of glass or deep water has to stop somewhere, or one ray can eat a whole frame budget. */
    @Test
    void aLongRunOfTranslucentBlocksTerminates() {
        TestWorld world = new TestWorld().texture("glass", TestWorld.solid(0x40FF0000));
        for (int z = 2; z < 40; z++) {
            world.cube(0, 0, z, "glass", BakedState.Alpha.TRANSLUCENT);
        }

        // Would not return at all if the fragment cap were not enforced.
        assertNotEquals(0, pixel(world, at(0.5, 0.5, 0.5, 0, 0)));
    }

    // --- sub-block geometry ---

    /** A slab is a box inside a block, so a ray above it has to pass over rather than hit the block. */
    @Test
    void aRayCanMissTheGeometryInsideABlock() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE))
                .box(0, 0, 5, 0, 0, 0, 16, 8, 16, "white");

        // Down the lower half of the block, into the slab.
        assertNotEquals(TestWorld.SKY, pixel(world, at(0.5, 0.2, 0.5, 0, 0)));
        // Down the upper half, through the air above it.
        assertEquals(TestWorld.SKY, pixel(world, at(0.5, 0.8, 0.5, 0, 0)));
    }

    @Test
    void aBoxIsHitOnTheSideTheRayArrivesAt() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE))
                .box(0, 0, 5, 4, 4, 4, 12, 12, 12, "white");

        assertEquals(shaded(0.8), red(pixel(world, at(0.5, 0.5, 0.5, 0, 0))), "arriving from the north, on a north face");
        assertEquals(shaded(1.0), red(pixel(world, at(0.5, 6.5, 5.5, 0, 90))), "arriving from above, on an up face");
    }

    /**
     * Grass seen steeply from above came out black: the entry face was taken from whichever slab plane the hit
     * point was nearest, which on a zero-thickness plane is arbitrary, so it reported DOWN and read its light
     * from inside the solid ground below.
     */
    @Test
    void aFlatPlaneIsNotShadedByAnArbitraryFace() {
        TestWorld world = new TestWorld()
                .texture("grass", TestWorld.solid(WHITE))
                .texture("dirt", TestWorld.solid(0xFF808080))
                .plane(0, 1, 0, "grass")
                .cube(0, 0, 0, "dirt", BakedState.Alpha.OPAQUE)
                .light(0, 0, 0, 0);

        // Steeply down onto the blades, from just south of them.
        int argb = pixel(world, at(0.5, 4.0, -0.4, 0, 70));

        assertEquals(shaded(1.0), red(argb), "no face shading and full light, not the dark block underneath");
    }

    @Test
    void shadeFalseSkipsFaceDarkening() {
        TestWorld shaded = new TestWorld().texture("white", TestWorld.solid(WHITE)).cube(0, 0, 5, "white", BakedState.Alpha.OPAQUE);
        TestWorld unshaded = new TestWorld().texture("white", TestWorld.solid(WHITE)).plane(0, 0, 5, "white");

        assertEquals(shaded(0.8), red(pixel(shaded, at(0.5, 0.5, 0.5, 0, 0))), "a normal north face is 0.8");
        assertEquals(shaded(1.0), red(pixel(unshaded, at(0.5, 0.5, 0.5, 0, 0))), "and a cross plane is not darkened at all");
    }

    // --- frame shape ---

    @Test
    void everyPixelOfAFrameIsWritten() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE));
        for (int x = -8; x <= 8; x++) {
            for (int y = -8; y <= 8; y++) {
                world.cube(x, y, 6, "white", BakedState.Alpha.OPAQUE);
            }
        }

        int[] out = new int[32 * 32];
        new RayCaster(world).render(world, at(0.5, 0.5, 0.5, 0, 0), 32, 32, out);

        for (int i = 0; i < out.length; i++) {
            assertEquals(0xFF, out[i] >>> 24, "pixel " + i + " should be opaque");
            assertNotEquals(TestWorld.SKY, out[i], "pixel " + i + " should have hit the wall");
        }
    }

    /** A wall square to the camera has to come out uniform, or the ray spread is wrong somewhere. */
    @Test
    void aFlatWallRendersFlat() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE));
        for (int x = -20; x <= 20; x++) {
            for (int y = -20; y <= 20; y++) {
                world.cube(x, y, 6, "white", BakedState.Alpha.OPAQUE);
            }
        }

        int[] out = new int[16 * 16];
        new RayCaster(world).render(world, at(0.5, 0.5, 0.5, 0, 0), 16, 16, out);

        for (int pixel : out) {
            assertEquals(out[0], pixel, "a flat wall lit evenly should be one color");
        }
    }

    // --- several elements in one block ---

    /**
     * The water in a waterlogged block sits in front of the block's own geometry, and the geometry is listed first.
     * Leaving the block at the first opaque element therefore drew the stair and threw the water away, which is why
     * waterlogged blocks came out dry.
     */
    @Test
    void anOpaqueElementDoesNotHideWaterInFrontOfIt() {
        TestWorld world = new TestWorld()
                .texture("stone", TestWorld.solid(WHITE))
                .texture("water", TestWorld.solid(0x800000FF))
                .waterlogged(0, 0, 4, "stone", "water");

        // Looking level at the top half of the block, which holds only water.
        int wet = pixel(world, at(0.5, 0.9, 0.5, 0, 0));
        assertNotEquals(TestWorld.SKY, wet);
        assertTrue(blue(wet) > red(wet), "the water should be the nearest surface, and it is blue");

        // And down into the half that holds the solid part, which still has water in front of it.
        int through = pixel(world, at(0.5, 4.0, 0.5, 0, -55));
        assertTrue(blue(through) > 0, "the water is still there");
        assertTrue(red(through) > 0, "and the solid part behind it still shows through");
    }

    /**
     * A turned plane is the only geometry in the format that is not axis-aligned, and the ray has to be bent into
     * its space to meet it. Unturned it is a flat sheet facing north; at 45 degrees it is a diagonal, so a ray that
     * misses the sheet hits the diagonal and the other way round.
     */
    @Test
    void aTurnedPlaneIsHitWhereTheTurnPutIt() {
        TestWorld flat = new TestWorld().texture("leaf", TestWorld.solid(WHITE)).plane(0, 0, 4, "leaf");
        TestWorld turned = new TestWorld().texture("leaf", TestWorld.solid(WHITE)).turnedPlane(0, 0, 4, "leaf", 45);

        // Straight on, both are in the way: the sheet across the block and the diagonal through it.
        assertNotEquals(TestWorld.SKY, pixel(flat, at(0.5, 0.5, 0.5, 0, 0)));
        assertNotEquals(TestWorld.SKY, pixel(turned, at(0.5, 0.5, 0.5, 0, 0)));

        // Now along the sheet's own plane. The only sides a cross draws are north and south, and a ray running that
        // way can only enter through east or west, which the model does not draw - so it passes straight through.
        // Turned, the same ray meets the diagonal through the block and hits a face that exists.
        assertEquals(TestWorld.SKY, pixel(flat, at(4.5, 0.5, 4.5, 90, 0)));
        assertNotEquals(TestWorld.SKY, pixel(turned, at(4.5, 0.5, 4.5, 90, 0)), "the diagonal reaches across the block");
    }

    /** Rescaling widens a turned box to keep its corners, so a 45 degree cross still fills its block. */
    @Test
    void aTurnedPlaneStillSpansItsBlock() {
        TestWorld turned = new TestWorld().texture("leaf", TestWorld.solid(WHITE)).turnedPlane(0, 0, 4, "leaf", 45);

        // A corner of the block. Without the rescale the diagonal stops short of it and this ray sees sky.
        assertNotEquals(TestWorld.SKY, pixel(turned, at(0.06, 0.5, 0.5, 0, 0)));
        assertNotEquals(TestWorld.SKY, pixel(turned, at(0.94, 0.5, 0.5, 0, 0)));
    }

    /**
     * A grass block is a cube of dirt with a second, coincident cube carrying the green fringe. Both are hit at
     * exactly the same distance, so the depth sort cannot order them and the list order has to: the overlay is
     * authored second and must composite in front. It did not, so grass block sides came out as bare dirt.
     */
    @Test
    void aCoincidentOverlayIsDrawnOverTheFaceItDecorates() {
        TestWorld world = new TestWorld()
                .texture("dirt", TestWorld.solid(0xFF806040))
                .texture("fringe", TestWorld.halfClear(0xFF00FF00))
                .decal(0, 0, 4, "dirt", "fringe");

        // A north face mirrors u, so the overlay's clear half is on the block's far side from the camera's x.
        int bare = pixel(world, at(0.8, 0.5, 0.5, 0, 0));
        assertTrue(red(bare) > green(bare), "where the overlay is clear the dirt shows through");

        // And the opaque half, which has to win despite being at the same depth as the dirt.
        int fringed = pixel(world, at(0.2, 0.5, 0.5, 0, 0));
        assertTrue(green(fringed) > red(fringed), "where the overlay is drawn it covers the dirt");
    }

    /**
     * A flat plane has two sides and the ray came in through one of them.
     *
     * <p>Both distances into a zero-thickness slab are the same number, so deciding the side by comparing them picks
     * the same one whichever way the ray travels. A flowerbed is exactly that shape, and it was reported as being seen
     * from underneath from every angle - drawn at the underside's half shade, which is what turned yellow wildflowers
     * into a dark ochre.
     */
    @Test
    void aFlatPlaneIsSeenFromTheSideTheRayCameFrom() {
        TestWorld world = new TestWorld().texture("white", TestWorld.solid(WHITE)).floorPlane(0, 0, 0, "white");

        assertEquals(shaded(1.0), red(pixel(world, at(0.5, 4, 0.5, 0, 90))), "from above, the lit top");
        assertEquals(shaded(0.5), red(pixel(world, at(0.5, -4, 0.5, 0, -90))), "from below, the dark underside");
    }

    private static int green(int argb) {
        return argb >> 8 & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }
}
