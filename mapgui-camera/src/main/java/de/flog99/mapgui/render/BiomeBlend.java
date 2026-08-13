package de.flog99.mapgui.render;

/**
 * The client's biome blend: a tint averaged over the square of biomes around a block rather than read off the one
 * under it.
 *
 * <p>Without it a biome border is a line drawn across the ground. Grass, foliage and water are one flat colour per
 * biome, so a plains meeting a forest changes green between two neighbouring blocks and the eye finds that edge
 * immediately - it reads as a mown lawn rather than as a wood starting. The client averages the colour over the 5x5
 * square of blocks centred on the one being drawn, which spreads the change over five blocks, and that is the whole
 * of the effect.
 *
 * <p><b>Twenty-five samples, four reads.</b> The client really does walk all twenty-five, and doing that here would
 * be twenty-five biome lookups per pixel that sees a leaf. It does not have to be: a biome is stored per 4x4x4 cell
 * and is one value across it, and a five-block run crosses exactly one cell boundary whatever it is aligned to - so
 * the square covers <b>at most four cells</b>, and the twenty-five samples are those four with weights that say how
 * many blocks each covers. Summing four weighted colours and dividing by twenty-five is the same arithmetic on the
 * same numbers, so it comes out bit-identical to the walk, and {@code BiomeBlendTest} checks it against a brute-force
 * one over every alignment.
 *
 * <p>The height is the block's own and is not blended, which is also what the client does: the square is flat, and
 * the biome it samples at each corner is the 3D one at that height. A cave biome under a forest tints its own
 * ceiling, and the surface above it stays a forest.
 */
public final class BiomeBlend {

    /** The client's own default {@code biomeBlendRadius}, in blocks, and the radius of its 5x5 square. */
    public static final int RADIUS = 2;

    /** Blocks across that square, which is what the two weights of one axis sum to. */
    private static final int ACROSS = RADIUS * 2 + 1;

    /** And blocks in it, which is what the client divides the summed channels by. */
    private static final int SAMPLES = ACROSS * ACROSS;

    /** Cells are 4 blocks across, which is the resolution a biome is stored at rather than a number chosen here. */
    private static final int CELL_BITS = 2;

    private BiomeBlend() {
    }

    /**
     * A block inside the square that falls in the lower of the two cells the axis spans, given the middle block.
     *
     * <p>Inside the square rather than the first block of the cell, which matters only at the edge of what was
     * captured: a sample the square itself does not reach could be outside the region while the block being drawn
     * is inside it.
     */
    public static int low(int middle) {
        return (border(middle) << CELL_BITS) - 1;
    }

    /** And one in the upper cell, which is the first block of it and so is inside the square by the same argument. */
    public static int high(int middle) {
        return border(middle) << CELL_BITS;
    }

    /**
     * How many of the five blocks fall in the lower cell, 1 to 4 - the rest fall in the upper one.
     *
     * <p>Never 0 and never 5, which is the "exactly one boundary" claim in another form: a run of five with cells of
     * four straddles a boundary at every alignment, so neither cell is ever unused and the pair below is never the
     * same cell twice.
     */
    public static int weight(int middle) {
        return (border(middle) << CELL_BITS) - (middle - RADIUS);
    }

    /** The one cell boundary the square straddles, as the index of the cell above it. */
    private static int border(int middle) {
        return middle + RADIUS >> CELL_BITS;
    }

    /**
     * The four corner colours averaged by how much of the square each one covers.
     *
     * <p>Channels summed and then divided, as the client does it, so the truncation lands in the same place.
     *
     * @param westWeight  {@link #weight} of the block's x, and {@code northWeight} the same for its z
     */
    public static int mix(int northWest, int northEast, int southWest, int southEast, int westWeight, int northWeight) {
        int eastWeight = ACROSS - westWeight;
        int southWeight = ACROSS - northWeight;

        // Blocks of the square each corner stands for, which sum to all twenty-five of them.
        int northWestShare = westWeight * northWeight;
        int northEastShare = eastWeight * northWeight;
        int southWestShare = westWeight * southWeight;
        int southEastShare = eastWeight * southWeight;

        int red = ((northWest >> 16 & 0xFF) * northWestShare + (northEast >> 16 & 0xFF) * northEastShare
                + (southWest >> 16 & 0xFF) * southWestShare + (southEast >> 16 & 0xFF) * southEastShare) / SAMPLES;
        int green = ((northWest >> 8 & 0xFF) * northWestShare + (northEast >> 8 & 0xFF) * northEastShare
                + (southWest >> 8 & 0xFF) * southWestShare + (southEast >> 8 & 0xFF) * southEastShare) / SAMPLES;
        int blue = ((northWest & 0xFF) * northWestShare + (northEast & 0xFF) * northEastShare
                + (southWest & 0xFF) * southWestShare + (southEast & 0xFF) * southEastShare) / SAMPLES;

        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
