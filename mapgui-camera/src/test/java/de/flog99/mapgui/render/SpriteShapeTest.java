package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item's picture extruded along its own outline rather than around the frame it is drawn in.
 *
 * <p>The bug this exists for was a sword: its blade runs corner to corner and touches its frame only at the tip, so a
 * rim built on the frame gave the tip thickness and the blade none. Measured on the real icons, a frame rim drew 176
 * pixels of a sword seen edge on and nothing at all of a bow, an apple or a pickaxe - 347 of 26.2's 796 icons never
 * touch their frame.
 */
class SpriteShapeTest {

    private static final int SIZE = 16;

    /** An icon of the stated shape, opaque where {@code rows} has a hash. */
    private static Texture icon(String... rows) {
        int[] argb = new int[SIZE * SIZE];
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                argb[y * SIZE + x] = rows[y].charAt(x) == '#' ? 0xFFCC8844 : 0;
            }
        }
        return Texture.opaqueOf(SIZE, SIZE, argb);
    }

    private static Texture blank() {
        return Texture.opaqueOf(SIZE, SIZE, new int[SIZE * SIZE]);
    }

    private static String row(String pattern) {
        return pattern + ".".repeat(SIZE - pattern.length());
    }

    private static List<MeshCube> shapeOf(Texture icon) {
        return SpriteShape.of(icon, 16, 1, 7.5f);
    }

    /**
     * A shape drawn inside its frame extrudes at its own edges, which is the whole point: a box whose rim sits where
     * the picture does not is a rim around nothing.
     */
    @Test
    void theRimFollowsThePictureRatherThanTheFrame() {
        // Four opaque texels in the middle of the icon, well clear of every edge of the frame.
        List<MeshCube> boxes = shapeOf(icon(
                row(""), row(""), row(""), row(""), row(""), row(""),
                row("......##"), row("......##")));

        assertEquals(1, boxes.size(), "one run, two identical rows, so one box");
        MeshCube box = boxes.getFirst();

        // The picture reads backwards against both axes here, so texels 6 and 7 across land at 8 and 10 from the far
        // side, and rows 6 and 7 down land at 8 and 10 up from the bottom.
        assertEquals(8f, box.minX(), 1e-4, "the box stops where the picture does");
        assertEquals(10f, box.maxX(), 1e-4);
        assertEquals(8f, box.minY(), 1e-4);
        assertEquals(10f, box.maxY(), 1e-4);

        for (Direction side : Direction.values()) {
            assertNotNull(box.face(side), side.key() + " is drawn, so the extrusion is closed");
        }
    }

    /** Rows that run the same way are one box, which is what keeps the count down to nine for a median icon. */
    @Test
    void rowsThatMatchAreOneBox() {
        List<MeshCube> tall = shapeOf(icon(
                row("####"), row("####"), row("####"), row("####")));

        assertEquals(1, tall.size(), "four identical rows are one box, not four");
        assertEquals(12f, tall.getFirst().minY(), 1e-4, "spanning all four rows");
        assertEquals(16f, tall.getFirst().maxY(), 1e-4);
    }

    /** And rows that differ are not, since that is where the outline turns. */
    @Test
    void aStaircaseIsOneBoxPerStep() {
        List<MeshCube> steps = shapeOf(icon(row("#"), row("##"), row("###")));

        assertEquals(3, steps.size());
    }

    /** Two runs in one row are two boxes, so a gap in the picture is a gap in the shape. */
    @Test
    void aGapInARowIsAGapInTheShape() {
        List<MeshCube> split = shapeOf(icon(row("##....##")));

        assertEquals(2, split.size());
        assertTrue(split.get(0).minX() >= split.get(1).maxX() || split.get(1).minX() >= split.get(0).maxX(),
                "the two boxes do not overlap");
    }

    /**
     * Each box reads its own rectangle of the icon, which is what stops the picture being scrambled.
     *
     * <p>A box covering the top left texel has to read the top left of the texture and not the whole of it shrunk.
     * Its rectangle is that texel inset by half a texel at each edge, which for a box one texel wide leaves every
     * corner on the texel's own middle - the only coordinate that can only ever floor to this texel and no other.
     */
    @Test
    void aBoxReadsItsOwnCornerOfThePicture() {
        MeshCube corner = shapeOf(icon(row("#"))).getFirst();
        float[] front = corner.face(Direction.NORTH);

        for (int slot = 0; slot < 4; slot++) {
            assertEquals(0.5f / 16, front[slot * 2], 1e-5, "u of corner " + slot);
            assertEquals(0.5f / 16, front[slot * 2 + 1], 1e-5, "v of corner " + slot);
        }
    }

    /** An icon with nothing in it is still drawn as something, which is what the whole-frame quad is left for. */
    @Test
    void anIconWithNothingToReadFallsBackToTheWholeFrame() {
        List<MeshCube> boxes = shapeOf(blank());

        assertEquals(1, boxes.size());
        assertEquals(0f, boxes.getFirst().minX(), 1e-4);
        assertEquals(16f, boxes.getFirst().maxX(), 1e-4);
    }

    /**
     * Every rim face of a box a texel wide reads that texel, and not the empty one beside it.
     *
     * <p>The bug this exists for was a bow, whose string runs corner to corner a pixel at a time. A rim face is one
     * line of the texture, so which line it lands on comes from a single coordinate - and a texture is sampled by
     * flooring, so a rectangle's far edge names the texel <i>past</i> it. The right and bottom rims of every box
     * therefore read whatever was next door, which on a diagonal is nothing, and half of the string vanished
     * whenever it was seen even slightly edge on.
     */
    @Test
    void everyRimOfATexelWideBoxReadsThatTexel() {
        // A diagonal, so that no box has a neighbour on any side that could stand in for its own picture.
        Texture diagonal = icon(row("#..."), row(".#.."), row("..#."), row("...#"));
        List<MeshCube> boxes = shapeOf(diagonal);
        assertEquals(4, boxes.size(), "one box per texel of the string");

        for (MeshCube box : boxes) {
            for (Direction side : Direction.values()) {
                float[] corners = box.faces()[side.ordinal()];
                assertNotNull(corners, side + " should be drawn");

                for (int corner = 0; corner < 4; corner++) {
                    int drawn = diagonal.sample(corners[corner * 2] * 16, corners[corner * 2 + 1] * 16);
                    assertTrue((drawn >>> 24) >= 128,
                            side + " corner " + corner + " reads a clear texel, so that face draws nothing");
                }
            }
        }
    }

    /**
     * Only what the renderer would draw counts as picture.
     *
     * <p>A texel the tracer passes through is not part of the shape, or the extrusion would have rim where nothing
     * draws - the two thresholds have to be the same one.
     */
    @Test
    void aTexelTooClearToDrawIsNotPartOfTheShape() {
        int[] argb = new int[SIZE * SIZE];
        argb[0] = 0x7FCC8844;

        assertEquals(1, SpriteShape.of(Texture.opaqueOf(SIZE, SIZE, argb), 16, 1, 7.5f).size(),
                "half-clear pixels leave nothing to extrude, so the whole frame stands in");
    }
}
