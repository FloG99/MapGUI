package de.flog99.mapgui;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What content is allowed to say about its own size.
 *
 * <p>Worth testing without a server because this is the one part of a wall a plugin author gets wrong
 * silently: a size that is quietly ignored looks like MapGUI losing the setting rather than like a ratio
 * with no multiple that fits.
 */
class WallSizeLimitTest {

    /** A builder with no services behind it, since nothing here gets as far as opening a wall. */
    private static WallDisplay.Builder wall() {
        return new WallDisplay.Builder(null, ignored -> {}, ignored -> {}).at(null, 0, 64, 0, BlockFace.SOUTH);
    }

    private static String sizeOf(WallDisplay.Builder builder) {
        WallLayout layout = builder.layout();
        return layout.cols() + "x" + layout.rows();
    }

    @Test
    void withNoLimitsTheSizeAskedForIsTheSizeGiven() {
        assertEquals("3x2", sizeOf(wall().size(3, 2)));
        assertEquals("6x6", sizeOf(wall().size(9, 9)), "still capped at a side of maps");
    }

    @Test
    void aFixedSizeIgnoresWhateverWasAskedFor() {
        assertEquals("2x2", sizeOf(wall().size(5, 4).fixedSize(2, 2)));
        assertEquals("2x2", sizeOf(wall().size(1, 1).fixedSize(2, 2)), "held up as well as down");
    }

    @Test
    void boundsHoldEachSideInsideItself() {
        assertEquals("2x1", sizeOf(wall().size(1, 1).sizeBetween(2, 1, 4, 3)));
        assertEquals("4x3", sizeOf(wall().size(6, 6).sizeBetween(2, 1, 4, 3)));
        assertEquals("3x2", sizeOf(wall().size(3, 2).sizeBetween(2, 1, 4, 3)), "left alone in the middle");
    }

    /** The ratio offers its own multiples and the nearest one to the request wins. */
    @Test
    void anAspectRatioSnapsToTheMultipleNearestTheRequest() {
        assertEquals("2x1", sizeOf(wall().size(2, 2).aspect(2, 1)));
        assertEquals("4x2", sizeOf(wall().size(4, 3).aspect(2, 1)));
        assertEquals("6x3", sizeOf(wall().size(6, 6).aspect(2, 1)));
        assertEquals("2x1", sizeOf(wall().size(1, 1).aspect(2, 1)), "the smallest it comes in");
    }

    @Test
    void anAspectRatioOnlyOffersMultiplesThatFitTheBounds() {
        assertEquals("2x1", sizeOf(wall().size(6, 6).aspect(2, 1).sizeBetween(1, 1, 3, 3)),
                "4x2 is outside the bounds, so the largest that fits is 2x1"
        );
    }

    /** A ratio no multiple of which fits falls through to the bounds rather than to nothing. */
    @Test
    void anImpossibleRatioLeavesTheBoundsInCharge() {
        assertEquals("2x2", sizeOf(wall().size(6, 6).aspect(5, 5).sizeBetween(1, 1, 2, 2)));
    }

    @Test
    void nonsenseIsRefusedWhereItIsWrittenRatherThanWhenAWallIsPlaced() {
        assertThrows(IllegalArgumentException.class, () -> wall().fixedSize(0, 1), "no such wall");
        assertThrows(IllegalArgumentException.class, () -> wall().fixedSize(1, 7), "bigger than a wall goes");
        assertThrows(IllegalArgumentException.class, () -> wall().sizeBetween(3, 1, 2, 1), "min above max");
        assertThrows(IllegalArgumentException.class, () -> wall().aspect(1, 0));
    }
}
