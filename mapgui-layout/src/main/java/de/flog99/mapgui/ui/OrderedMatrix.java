package de.flog99.mapgui.ui;

import org.jetbrains.annotations.ApiStatus;

import java.util.Random;

/**
 * The threshold tile an ordered dither compares against.
 *
 * <p>An ordered mode is a threshold per pixel position and nothing more: given how far between two palette
 * entries a color sits, the tile decides which of the two this particular pixel gets. Which tile it is decides
 * everything about how the result looks - how fine the texture is, whether a grid shows, and how well the
 * result compresses.
 *
 * <p>Sizes are powers of two so the wrap is a mask rather than a modulo, which matters at one lookup per pixel.
 * Immutable, so one tile serves every palette and every thread.
 */
@ApiStatus.Experimental
public final class OrderedMatrix {

    private final int size;
    private final int mask;
    private final int[] thresholds;

    private OrderedMatrix(int[] thresholds, int size) {
        this.size = size;
        this.mask = size - 1;
        this.thresholds = thresholds;
    }

    /**
     * How many steps between two entries this tile can distinguish, which is one more than its largest
     * threshold - a color sitting exactly on the far entry has to beat every threshold in the tile.
     */
    public int levels() {
        return thresholds.length;
    }

    /** The threshold for a pixel, wrapping. Negative coordinates wrap too, which a mask does for free. */
    public int threshold(int x, int y) {
        return thresholds[(y & mask) * size + (x & mask)];
    }

    /**
     * The classic Bayer 4x4, written out rather than generated.
     *
     * <p>This exact arrangement is what every dithered gradient in MapGUI has always been drawn with, so it is
     * kept literally rather than derived from the 2x2 recurrence - the recurrence gives a matrix with the same
     * properties in a different arrangement, and changing every existing render to save four lines is a poor
     * trade.
     */
    public static OrderedMatrix bayer4() {
        return BAYER_4;
    }

    /** Bayer 8x8, grown from {@link #bayer4()} by the standard recurrence, so the coarse pattern is the same. */
    public static OrderedMatrix bayer8() {
        return BAYER_8;
    }

    /**
     * A 16x16 blue noise tile: thresholds arranged so that any level's pixels are spread as evenly as the tile
     * allows without ever falling into a grid.
     *
     * <p>Generated on first use rather than typed in as 256 numbers, which would be unreviewable. It takes a
     * few milliseconds and only happens if something actually asks for {@link Dither#BLUE_NOISE}.
     */
    public static OrderedMatrix blueNoise() {
        return BlueNoise.TILE;
    }

    /** The tile a mode is drawn with, or null for {@link Dither#NONE} and for the diffusing modes. */
    static OrderedMatrix of(Dither mode) {
        return switch (mode) {
            case ORDERED -> bayer4();
            case ORDERED_FINE -> bayer8();
            case BLUE_NOISE -> blueNoise();
            // Enumerated rather than defaulted, for the same reason Dither#diffuses() has no default arm: a new
            // ordered mode must fail to compile here instead of reaching DitheredPalette with a null tile.
            case NONE, FLOYD_STEINBERG, ATKINSON, SIERRA_LITE -> null;
        };
    }

    private static final OrderedMatrix BAYER_4 = new OrderedMatrix(new int[]{
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5,
    }, 4);

    private static final OrderedMatrix BAYER_8 = grow(BAYER_4);

    /**
     * One step of the Bayer recurrence: four copies of the tile at four times the scale, offset by the 2x2
     * pattern {@code 0 2 / 3 1}. The offsets are what keeps the result dispersed rather than clustered.
     */
    private static OrderedMatrix grow(OrderedMatrix from) {
        int size = from.size * 2;
        int[] grown = new int[size * size];
        int[] offset = {0, 2, 3, 1};

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int quadrant = (y >= from.size ? 2 : 0) + (x >= from.size ? 1 : 0);
                grown[y * size + x] = 4 * from.threshold(x, y) + offset[quadrant];
            }
        }
        return new OrderedMatrix(grown, size);
    }

    /** Built on first touch, and only then - see {@link #blueNoise()}. */
    private static final class BlueNoise {
        static final OrderedMatrix TILE = new OrderedMatrix(voidAndCluster(BLUE_NOISE_SIZE), BLUE_NOISE_SIZE);
    }

    /**
     * Sixteen, so 256 levels - one for every step there could be between two palette entries, and small enough
     * that the tile stays in cache and that generating it costs milliseconds rather than seconds.
     */
    private static final int BLUE_NOISE_SIZE = 16;

    /**
     * How wide the energy kernel is, in pixels. Ulichney's own figure, kept rather than tuned - it is what the
     * method was published with, and a tile that came out of a number somebody preferred would be harder to
     * defend than one that came out of the paper.
     */
    private static final double SIGMA = 1.5;

    /** Fixed, because a generated tile that differed between servers would make a render unreproducible. */
    private static final long SEED = 0x5EEDB10EL;

    /**
     * Ulichney's void-and-cluster, which is what makes a tile blue rather than merely random.
     *
     * <p>The idea in one sentence: keep a binary pattern, repeatedly move the pixel out of the tightest cluster
     * into the largest void until neither exists, then rank every position by the order in which it enters that
     * pattern. The rank is the threshold, so each level's pixels sit as far from each other as the tile allows -
     * which is what "no visible grid and no clumps" means.
     *
     * <p>Both questions are answered from one field: the pattern filtered by a wrap-around Gaussian. The
     * tightest cluster is the highest-energy set pixel, the largest void the lowest-energy unset one. The field
     * is kept current by adding or subtracting one kernel per change rather than refiltering, which is what
     * makes this cheap enough to do at runtime.
     *
     * <p>Ulichney's phases two and three collapse into one loop here, and that is not a shortcut. Past half
     * full the minority is the empty pixels, so phase three asks for the tightest cluster of <i>those</i> - and
     * with a kernel that wraps over the whole tile, the empty pixels' energy is a constant minus this field, so
     * their tightest cluster is at the same position as the largest void. One search answers both.
     */
    private static int[] voidAndCluster(int size) {
        int count = size * size;
        double[] kernel = gaussian(size);
        boolean[] pattern = new boolean[count];
        double[] energy = new double[count];

        // A tenth, which is Ulichney's own figure: enough of a pattern for the swapping to have something to
        // even out, and sparse enough that what follows is mostly voids to fill.
        int ones = Math.max(1, count / 10);
        Random random = new Random(SEED);
        for (int placed = 0; placed < ones; ) {
            int at = random.nextInt(count);
            if (!pattern[at]) {
                toggle(pattern, energy, kernel, size, at, true);
                placed++;
            }
        }

        // Stable once the tightest cluster's own position is also the largest void, meaning there is nowhere
        // better for that pixel to go. The cap guards against two positions swapping forever, which ties would
        // allow; the loop settles in a small fraction of it.
        for (int step = 0; step < count * count; step++) {
            int cluster = extreme(pattern, energy, true, true);
            toggle(pattern, energy, kernel, size, cluster, false);
            int hole = extreme(pattern, energy, false, false);
            if (hole == cluster) {
                toggle(pattern, energy, kernel, size, cluster, true);
                break;
            }
            toggle(pattern, energy, kernel, size, hole, true);
        }

        boolean[] prototype = pattern.clone();
        double[] prototypeEnergy = energy.clone();
        int[] rank = new int[count];

        // Downward from the prototype: the last pixel left standing as a cluster is the first to appear in the
        // tile, so removing them in order ranks them from the back.
        for (int level = ones - 1; level >= 0; level--) {
            int cluster = extreme(pattern, energy, true, true);
            toggle(pattern, energy, kernel, size, cluster, false);
            rank[cluster] = level;
        }

        // Upward from the prototype, filling voids until the tile is full.
        System.arraycopy(prototype, 0, pattern, 0, count);
        System.arraycopy(prototypeEnergy, 0, energy, 0, count);
        for (int level = ones; level < count; level++) {
            int hole = extreme(pattern, energy, false, false);
            toggle(pattern, energy, kernel, size, hole, true);
            rank[hole] = level;
        }
        return rank;
    }

    /** The set or unset position with the highest or lowest energy. */
    private static int extreme(boolean[] pattern, double[] energy, boolean set, boolean highest) {
        int found = -1;
        double best = 0;

        for (int at = 0; at < pattern.length; at++) {
            if (pattern[at] != set) continue;

            double value = energy[at];
            if (found < 0 || (highest ? value > best : value < best)) {
                best = value;
                found = at;
            }
        }
        return found;
    }

    /** Sets or clears one position, moving its kernel into or out of the energy field. */
    private static void toggle(boolean[] pattern, double[] energy, double[] kernel, int size, int at, boolean set) {
        pattern[at] = set;
        int fromY = at / size;
        int fromX = at % size;

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double weight = kernel[Math.abs(y - fromY) * size + Math.abs(x - fromX)];
                energy[y * size + x] += set ? weight : -weight;
            }
        }
    }

    /**
     * The kernel by offset, wrapped: a tile repeats, so the distance between two positions is the shorter way
     * round. Indexed by absolute offset, which is all {@link #toggle} needs of a symmetric kernel.
     */
    private static double[] gaussian(int size) {
        double[] weights = new double[size * size];
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                int wrapY = Math.min(dy, size - dy);
                int wrapX = Math.min(dx, size - dx);
                weights[dy * size + dx] = Math.exp(-(wrapX * wrapX + wrapY * wrapY) / (2 * SIGMA * SIGMA));
            }
        }
        return weights;
    }
}
