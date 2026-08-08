package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one map's changes are split into, which is a judgement about bytes against packets rather than a
 * geometry question - so these are cases where the right answer is arguable, and the point is which way.
 */
class PatchesTest {

    private static final int MAP = 128;

    private static List<Rect> plan(MapSurface surface) {
        return surface.dirtyRegions();
    }

    private static void box(MapSurface surface, int x, int y, int width, int height) {
        for (int row = y; row < y + height; row++) {
            for (int col = x; col < x + width; col++) {
                surface.set(col, row, (byte) 42);
            }
        }
    }

    private static long payload(List<Rect> regions) {
        long bytes = 0;
        for (Rect region : regions) bytes += (long) region.width() * region.height();
        return bytes;
    }

    @Test
    void nothingChangedIsNothingToSend() {
        assertEquals(List.of(), plan(new MapSurface(MAP, MAP)));
    }

    @Test
    void oneShapeIsOneRectangle() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 10, 20, 30, 40);

        assertEquals(List.of(new Rect(10, 20, 30, 40)), plan(surface));
    }

    @Test
    void aFullRedrawIsStillOnePacket() {
        MapSurface surface = new MapSurface(MAP, MAP);
        surface.markAllDirty();

        assertEquals(List.of(new Rect(0, 0, MAP, MAP)), plan(surface));
    }

    /** The case the whole thing exists for: the box around both is the entire map. */
    @Test
    void twoCornersGoSeparatelyRatherThanAsTheMapBetweenThem() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 0, 0, 16, 16);
        box(surface, 112, 112, 16, 16);

        List<Rect> regions = plan(surface);

        assertEquals(List.of(new Rect(0, 0, 16, 16), new Rect(112, 112, 16, 16)), regions);
        assertEquals(512, payload(regions), "against 16384 for the box around the two");
    }

    /** Rows are exact, so a full-width strip either end costs the strips and not the body. */
    @Test
    void aHeaderAndAFooterLeaveTheBodyAlone() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 0, 0, MAP, 8);
        box(surface, 0, 120, MAP, 8);

        assertEquals(List.of(new Rect(0, 0, MAP, 8), new Rect(0, 120, MAP, 8)), plan(surface));
    }

    /** Two shapes of different widths, which a greedy pass gets wrong by widening the first and never narrowing. */
    @Test
    void aScrollbarBesideABodyDoesNotWidenTheHeaderOverIt() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 0, 0, MAP, 8);
        box(surface, 124, 8, 4, 120);

        assertEquals(List.of(new Rect(0, 0, MAP, 8), new Rect(124, 8, 4, 120)), plan(surface));
    }

    /**
     * The other half of the judgement. Splitting has to be worth a packet, so changes with almost nothing
     * between them stay together even though a split would send fewer bytes.
     */
    @Test
    void changesAlmostTouchingAreNotWorthAPacketApart() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 0, 0, MAP, 2);
        box(surface, 0, 4, MAP, 2);

        assertEquals(List.of(new Rect(0, 0, MAP, 6)), plan(surface), "the two clean rows cost less than a packet");
    }

    /**
     * A diagonal is the shape with no good answer: every row moved and no two of them line up, so one box is
     * the whole map and one packet per row is 128 packets. Neither is what comes out - splitting it into k
     * squares costs {@code 16384 / k + 1024k}, which is least at four, and four is what it finds.
     */
    @Test
    void aDiagonalIsChunkedRatherThanSentWholeOrRowByRow() {
        MapSurface surface = new MapSurface(MAP, MAP);
        for (int row = 0; row < MAP; row++) surface.set(row, row, (byte) 42);

        List<Rect> regions = plan(surface);

        assertEquals(4, regions.size());
        assertEquals(4096, payload(regions), "against 16384 for the box and 128 packets for the rows");
    }

    /**
     * However badly a frame is arranged, the price on a packet caps what one map can turn into.
     *
     * <p>Full-width strips gapped just past where merging pays is the arrangement that maximises the count -
     * every gap is worth splitting and none of them is wide enough to run out of map. Scattering changes more
     * widely than this does not beat it: past a point the box is unavoidable and the split stops being worth
     * having at all.
     */
    @Test
    void theWorstArrangementStillFitsInAHandfulOfPackets() {
        MapSurface surface = new MapSurface(MAP, MAP);
        for (int row = 0; row < MAP; row += 10) box(surface, 0, row, MAP, 1);

        List<Rect> regions = plan(surface);

        assertTrue(regions.size() <= 16, "split into " + regions.size() + " packets for one map");
        assertTrue(payload(regions) <= (long) surface.dirtyBounds().width() * surface.dirtyBounds().height(),
                "splitting sent more bytes than the box would have");
    }

    /** Splitting must never come out worse than the box it is an alternative to, whatever the shape. */
    @Test
    void splittingNeverCostsMoreThanTheBoxWouldHave() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 4, 4, 20, 20);
        box(surface, 100, 30, 8, 60);
        box(surface, 0, 100, MAP, 4);
        surface.set(64, 127, (byte) 42);

        List<Rect> regions = plan(surface);
        Rect box = surface.dirtyBounds();

        assertTrue(payload(regions) < (long) box.width() * box.height(),
                "sent " + payload(regions) + " bytes in " + regions.size() + " packets against " + box);
    }

    /** Regions are what gets copied out, so they have to be in bounds and in order. */
    @Test
    void regionsComeOutTopToBottomAndInsideTheSurface() {
        MapSurface surface = new MapSurface(MAP, MAP);
        box(surface, 0, 0, 16, 16);
        box(surface, 100, 60, 20, 8);
        box(surface, 112, 112, 16, 16);

        List<Rect> regions = plan(surface);

        int previous = -1;
        for (Rect region : regions) {
            assertTrue(region.y() > previous, "regions overlap or are out of order: " + regions);
            previous = region.bottom() - 1;

            assertTrue(region.x() >= 0 && region.right() <= MAP, region.toString());
            assertTrue(region.y() >= 0 && region.bottom() <= MAP, region.toString());
        }
    }
}
