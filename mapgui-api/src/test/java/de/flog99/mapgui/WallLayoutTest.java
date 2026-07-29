package de.flog99.mapgui;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WallLayoutTest {

    private static WallLayout on(BlockFace facing) {
        return WallLayout.anchoredAt(0, 64, 0, facing);
    }

    /**
     * A map faces whoever is looking at it, so the viewer's right is the opposite of the obvious one.
     * Getting this backwards mirrors the whole wall, which reads as "the video is wrong" rather than
     * "an axis is wrong".
     */
    @Test
    void rightIsTheViewersRightNotTheFacesOwn() {
        assertEquals(BlockFace.EAST, on(BlockFace.SOUTH).right(), "stood south of it, looking north");
        assertEquals(BlockFace.WEST, on(BlockFace.NORTH).right());
        assertEquals(BlockFace.NORTH, on(BlockFace.EAST).right());
        assertEquals(BlockFace.SOUTH, on(BlockFace.WEST).right());
    }

    @Test
    void aWallOnABlockFaceStandsUpright() {
        for (BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            assertEquals(BlockFace.UP, on(facing).up(), "a wall takes its up from the world");
        }
    }

    /**
     * A map on the ground has no world up to use, and cannot pick one either: the client draws every frame
     * on a horizontal face at the same angle, so this has to match that angle or the picture and the cursor
     * end up a quarter turn apart.
     */
    @Test
    void aMapOnTheFloorTakesTheAngleTheClientDrawsIt() {
        WallLayout floor = WallLayout.anchoredAt(0, 64, 0, BlockFace.UP);
        assertEquals(BlockFace.NORTH, floor.up(), "a floor frame's top edge points north");
        assertEquals(BlockFace.EAST, floor.right(), "looking down with north up, right is east");

        WallLayout ceiling = WallLayout.anchoredAt(0, 64, 0, BlockFace.DOWN);
        assertEquals(BlockFace.SOUTH, ceiling.up(), "tipped the other way, so the top edge is south");
        assertEquals(BlockFace.EAST, ceiling.right(), "looking up with south up, right is still east");
    }

    @Test
    void stretchingGrowsRightAndUpFromTheAnchor() {
        // Wall on the south face, so right is east (+x) and up is +y.
        WallLayout grid = on(BlockFace.SOUTH).stretchedTo(2, 66, 0);

        assertEquals(3, grid.cols());
        assertEquals(3, grid.rows());
        assertEquals(0, grid.anchorX(), "the anchor never moves");
        assertEquals(64, grid.anchorY());
    }

    /**
     * Sizing the other way has to work too, so the anchor steps over to whichever corner turns out to be
     * the bottom left. Sizes stay positive either way - a negative width would draw nothing at all.
     */
    @Test
    void sizingBackwardsMovesTheAnchorToTheFarCorner() {
        // Right is east, so a target three west and two down spans four by three the other way.
        WallLayout layout = on(BlockFace.SOUTH).stretchedTo(-3, 62, 0);

        assertEquals(4, layout.cols());
        assertEquals(3, layout.rows());
        assertEquals(-3, layout.anchorX(), "the anchor is now the westmost block");
        assertEquals(62, layout.anchorY(), "and the lowest");
    }

    @Test
    void oneAxisEachWayStillGivesPositiveSizes() {
        WallLayout layout = on(BlockFace.SOUTH).stretchedTo(2, 62, 0);

        assertEquals(3, layout.cols());
        assertEquals(3, layout.rows());
        assertEquals(0, layout.anchorX(), "growing east, so the anchor keeps its x");
        assertEquals(62, layout.anchorY(), "growing down, so it takes the target's y");
    }

    /** Clicking the anchor itself is a legitimate one-by-one wall. */
    @Test
    void theSameBlockTwiceIsOneTile() {
        WallLayout layout = on(BlockFace.SOUTH).stretchedTo(0, 64, 0);

        assertEquals(1, layout.cols());
        assertEquals(1, layout.rows());
        assertEquals(0, layout.anchorX());
    }

    /** Capped in the direction of travel, so the block first clicked stays on the wall. */
    @Test
    void sizingBackwardsPastTheLimitKeepsTheAnchorOnTheEdge() {
        WallLayout layout = on(BlockFace.SOUTH).stretchedTo(-40, 64, 0);

        assertEquals(WallLayout.MAX_SIDE, layout.cols());
        assertEquals(1 - WallLayout.MAX_SIDE, layout.anchorX(), "six wide, ending at the clicked block");
    }

    @Test
    void neitherSideGrowsPastTheLimit() {
        WallLayout grid = on(BlockFace.SOUTH).stretchedTo(40, 200, 0);

        assertEquals(WallLayout.MAX_SIDE, grid.cols());
        assertEquals(WallLayout.MAX_SIDE, grid.rows());
    }

    /** Off-plane blocks are projected rather than refused, so the preview never freezes. */
    @Test
    void aBlockOffThePlaneIsProjectedOntoIt() {
        WallLayout grid = on(BlockFace.SOUTH).stretchedTo(1, 65, 9);

        assertEquals(2, grid.cols(), "the z offset is along the face, so it is dropped");
        assertEquals(2, grid.rows());
    }

    @Test
    void blocksAreLaidOutAlongTheTwoAxes() {
        WallLayout grid = on(BlockFace.SOUTH).resized(3, 2);

        assertEquals(0, grid.blockX(0, 0));
        assertEquals(64, grid.blockY(0, 0));
        assertEquals(2, grid.blockX(2, 0), "two to the east");
        assertEquals(65, grid.blockY(0, 1), "one up");
        assertEquals(0, grid.blockZ(2, 1), "never leaves the plane");
    }

    /**
     * The cursor. A ray trace passes through our unreal frames and hits the block behind, reporting a
     * fractional point on the plane the map is drawn on - so this is the whole of pointing at a wall.
     */
    @Test
    void aimingMapsAWorldPointOntoTheSurface() {
        // South face, so right is east and up is world up. Two by two, anchored at 0/64/0.
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);

        WallLayout.Aim bottomLeft = layout.aimedAt(0.0, 64.0, 1.0);
        assertEquals(0, bottomLeft.x());
        assertEquals(255, bottomLeft.y(), "the very bottom row, not one past it");

        WallLayout.Aim middle = layout.aimedAt(1.0, 65.0, 1.0);
        assertEquals(128, middle.x());
        assertEquals(127, middle.y());

        WallLayout.Aim nearTop = layout.aimedAt(1.5, 65.75, 1.0);
        assertEquals(192, nearTop.x());
        assertEquals(31, nearTop.y());
    }

    /** Every point on the wall has to land inside the surface, or drawing a cursor there would throw. */
    @Test
    void everyAimLandsInsideTheSurface() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 3);

        // Stepped by whole numbers and divided, since accumulating 0.05 drifts onto the far edge and the
        // far edge is deliberately a miss.
        for (int i = 0; i < 40; i++) {
            for (int j = 0; j < 60; j++) {
                double across = i / 20.0;
                double up = j / 20.0;

                WallLayout.Aim aim = layout.aimedAt(across, 64 + up, 1.0);
                assertNotNull(aim, "on the wall at " + across + "/" + up);
                assertTrue(aim.x() >= 0 && aim.x() < layout.pixelWidth(), "x out of range: " + aim.x());
                assertTrue(aim.y() >= 0 && aim.y() < layout.pixelHeight(), "y out of range: " + aim.y());
            }
        }
    }

    /** How far along the face the point is must not matter - the map hangs slightly in front of it. */
    @Test
    void distanceAlongTheFaceIsIgnored() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);

        assertEquals(layout.aimedAt(1.0, 65.0, 1.0), layout.aimedAt(1.0, 65.0, 40.0));
    }

    @Test
    void aimingOffTheWallMissesRatherThanClamping() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);

        assertNull(layout.aimedAt(-0.5, 65.0, 1.0), "left of it");
        assertNull(layout.aimedAt(1.0, 63.5, 1.0), "below it");
        assertNull(layout.aimedAt(2.5, 65.0, 1.0), "past the right edge");
        assertNull(layout.aimedAt(1.0, 66.5, 1.0), "above the top");
    }

    /**
     * A wall whose axis runs negative counts from the far side of the anchor block, since a block spans a
     * whole unit. Getting this wrong mirrors the cursor against the picture.
     */
    @Test
    void anAxisRunningNegativeCountsFromTheBlocksFarSide() {
        // North face, so right is west: the wall covers x = 0 and x = -1, and x = 1 is its origin edge.
        WallLayout layout = on(BlockFace.NORTH).resized(2, 1);

        assertEquals(0, layout.aimedAt(0.999, 64.0, 0.0).x(), "just inside the origin corner");
        assertEquals(128, layout.aimedAt(0.0, 64.0, 0.0).x(), "the edge where the second block starts");
        assertEquals(192, layout.aimedAt(-0.5, 64.0, 0.0).x(), "and its middle");
        assertNull(layout.aimedAt(1.5, 64.0, 0.0), "the wrong side of the origin entirely");
    }

    /** A map on the ground has its own axes, and the cursor has to follow them. */
    @Test
    void aimingWorksOnAFloorToo() {
        WallLayout floor = WallLayout
                .anchoredAt(0, 64, 0, BlockFace.UP)
                .resized(2, 2);

        // Up is north, which is -z, so the picture's top edge is the lowest z.
        assertEquals(0, floor.aimedAt(0.0, 65.0, 1.0).x());
        assertEquals(255, floor.aimedAt(0.0, 65.0, 1.0).y(), "z = 1 is the bottom of the picture");
        assertEquals(0, floor.aimedAt(0.0, 65.0, -0.999).y(), "and the far edge is the top");
        assertNull(floor.aimedAt(0.0, 65.0, -1.0), "one step further is off the wall");
    }

    /**
     * A pixel has to name the right map, and the maps are numbered bottom row first while the surface
     * counts down from the top - so this is where that flip lives, tested once instead of inline.
     */
    @Test
    void aPixelNamesTheMapItBelongsTo() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);

        // Bottom left of the picture is the first map, since maps start at the bottom row.
        assertEquals(0, layout.tileOf(new WallLayout.Aim(0, 255)));
        assertEquals(1, layout.tileOf(new WallLayout.Aim(200, 255)), "bottom right");
        assertEquals(2, layout.tileOf(new WallLayout.Aim(0, 0)), "top left is the second row");
        assertEquals(3, layout.tileOf(new WallLayout.Aim(200, 0)), "top right");
    }

    /** Markers are placed inside one map, so a surface pixel has to lose its tile's offset. */
    @Test
    void aTileOriginTurnsASurfacePixelIntoAMapLocalOne() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);
        WallLayout.Aim aim = new WallLayout.Aim(200, 30);

        assertEquals(128, layout.tileOriginX(aim.x()));
        assertEquals(0, layout.tileOriginY(aim.y()));
        assertEquals(72, aim.x() - layout.tileOriginX(aim.x()), "inside its own map");
        assertEquals(30, aim.y() - layout.tileOriginY(aim.y()));
    }

    /** Blocks count up from the bottom and pixels count down from the top. */
    @Test
    void theBottomRowOfBlocksIsTheBottomOfThePicture() {
        WallLayout grid = on(BlockFace.SOUTH).resized(2, 2);

        assertEquals(128, grid.surfaceY(0), "row nought is the lower half");
        assertEquals(0, grid.surfaceY(1));
        assertEquals(0, grid.surfaceX(0));
        assertEquals(128, grid.surfaceX(1));
        assertEquals(256, grid.pixelWidth());
        assertEquals(256, grid.pixelHeight());
    }

    /**
     * Which side of the map a point is on, which is what tells someone looking at the picture from someone
     * looking at the back of the block it hangs on. Both land on the same plane; only the sign differs.
     */
    @Test
    void depthIsPositiveInFrontAndNegativeBehind() {
        WallLayout layout = on(BlockFace.SOUTH).resized(2, 2);

        assertEquals(0, layout.depthOf(0.5, 64.5, 1.0), 1e-9, "on the map itself");
        assertTrue(layout.depthOf(0.5, 64.5, 3.0) > 0, "stood south of it, where a viewer is");
        assertTrue(layout.depthOf(0.5, 64.5, -2.0) < 0, "round the back");
    }

    /** On a floor the plane is the top face, so depth runs up rather than along a horizontal axis. */
    @Test
    void depthOnAFloorIsMeasuredUpwards() {
        WallLayout floor = WallLayout.anchoredAt(0, 64, 0, BlockFace.UP).resized(2, 2);

        assertEquals(0, floor.depthOf(0.5, 65.0, 0.5), 1e-9, "the top face is where the map is");
        assertTrue(floor.depthOf(0.5, 66.6, 0.5) > 0, "stood on it");
        assertTrue(floor.depthOf(0.5, 64.2, 0.5) < 0, "inside the block below it");
    }

    /** Without a margin the edge is the edge - a point just off the wall is a miss. */
    @Test
    void aPointOffTheWallMissesWhenThereIsNoMargin() {
        WallLayout layout = on(BlockFace.SOUTH).resized(1, 1);

        assertNull(layout.aimedAt(-0.1, 64.5, 0.0), "left of it");
        assertNull(layout.aimedAt(1.1, 64.5, 0.0), "right of it");
    }

    /**
     * With one, overshooting sticks to the nearest edge instead. That is what makes drawing along a border
     * possible: the last row of pixels is a fraction of a block wide to aim at.
     */
    @Test
    void aMarginPinsAnOvershootToTheEdge() {
        WallLayout layout = on(BlockFace.SOUTH).resized(1, 1);
        int margin = 20;

        // A tenth of a block past the left edge is about 13 pixels out, so inside a 20 pixel margin.
        WallLayout.Aim left = layout.aimedAt(-0.1, 64.5, 0.0, margin);
        assertNotNull(left, "inside the margin");
        assertEquals(0, left.x(), "pinned to the first column rather than reported as negative");

        WallLayout.Aim right = layout.aimedAt(1.1, 64.5, 0.0, margin);
        assertNotNull(right);
        assertEquals(127, right.x(), "pinned to the last column");

        WallLayout.Aim below = layout.aimedAt(0.5, 63.9, 0.0, margin);
        assertNotNull(below);
        assertEquals(127, below.y(), "pinned to the bottom row");
    }

    @Test
    void aMarginStillRunsOut() {
        WallLayout layout = on(BlockFace.SOUTH).resized(1, 1);

        // Half a block past the edge is 64 pixels, well beyond a 20 pixel margin.
        assertNull(layout.aimedAt(-0.5, 64.5, 0.0, 20));
        assertNull(layout.aimedAt(0.5, 63.5, 0.0, 20));
    }

    /**
     * Every pixel has to name a point that names it back, on all six faces.
     *
     * <p>Load-bearing because of the margin: a pinned aim is measured against the world through this, so an
     * axis dropped or flipped here would put the visibility check a fraction of a block off the picture -
     * inside the blocks it hangs on - and clicks would stop landing from angles nobody would think to try.
     */
    @Test
    void aPixelNamesAPointThatNamesThePixel() {
        for (BlockFace facing : BlockFace.values()) {
            if (!facing.isCartesian() || facing == BlockFace.SELF) continue;

            WallLayout layout = on(facing).resized(2, 2);
            for (WallLayout.Aim aim : new WallLayout.Aim[]{
                    new WallLayout.Aim(0, 0), new WallLayout.Aim(255, 255),
                    new WallLayout.Aim(255, 0), new WallLayout.Aim(0, 255),
                    new WallLayout.Aim(70, 199)}) {

                double x = layout.pixelX(aim);
                double y = layout.pixelY(aim);
                double z = layout.pixelZ(aim);

                assertEquals(0, layout.depthOf(x, y, z), 1e-9, facing + " puts its pixels on the plane");
                assertEquals(aim, layout.aimedAt(x, y, z), facing + " round trip");
            }
        }
    }

    /** A pinned aim names a point on the picture, not the one off the edge that was pinned back. */
    @Test
    void aPinnedPixelPointsAtThePictureRatherThanPastIt() {
        WallLayout layout = on(BlockFace.SOUTH).resized(1, 1);

        WallLayout.Aim below = layout.aimedAt(0.5, 63.9, 0.0, 20);
        assertNotNull(below);
        assertTrue(layout.pixelY(below) > 64.0, "back above the bottom edge, not still under it");
        assertEquals(below, layout.aimedAt(layout.pixelX(below), layout.pixelY(below), layout.pixelZ(below)));
    }
}
