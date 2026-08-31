package de.flog99.mapgui;

import de.flog99.mapgui.ui.Oklab;
import org.bukkit.map.MapPalette;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Would matching in a perceptual space beat what the palette does now, above the dark range?
 *
 * <p>The question the roadmap asked, answered with a number rather than a preference. Three matchers pick an
 * entry for the same colour - vanilla's weighting, which is what the bright range uses; the dark table's own
 * rule, extended upward; and one that searches in {@link Oklab} directly - and all three are scored by
 * <b>CIELAB</b>, which none of them uses.
 *
 * <p>The referee has to be a fourth thing. Scoring an Oklab matcher in Oklab would prove only that minimising a
 * quantity minimises it, and every one of these could be made to win by picking the yardstick afterwards. CIELAB
 * is the older perceptual space and disagrees with Oklab in the places perceptual spaces disagree, which is what
 * makes it worth asking.
 *
 * <p><b>It is a test rather than a change because of what it found.</b> Oklab is better on saturated colour and
 * slightly worse on grey, and grey is what a screen is mostly made of. The numbers are in
 * {@link #whereOklabWinsAndByHowMuch}, and the reason to keep this around is that they can be re-run: if the
 * palette ever changes, or if the mix of what people draw does, the answer might not be the same one.
 */
@SuppressWarnings("removal")
class PerceptualMatcherAbTest {

    /** Below this the finer dark table applies and this is not the question. See {@code PaletteLut.DARK_TOP}. */
    private static final int DARK = 64;

    /** Five-bit rounding, which every matcher here works from, so the table resolution is not what is compared. */
    private static Color rounded(Color wanted) {
        return new Color(
                (wanted.getRed() >> 3) * 255 / 31,
                (wanted.getGreen() >> 3) * 255 / 31,
                (wanted.getBlue() >> 3) * 255 / 31);
    }

    private static Color vanilla(Color wanted) {
        return MapColors.INSTANCE.color(MapPalette.matchColor(rounded(wanted)));
    }

    /** Nearest by plain RGB distance, which is what the dark table falls back to for anything with a hue. */
    private static Color plainRgb(Color wanted) {
        Color from = rounded(wanted);
        return best(entry -> {
            long dr = from.getRed() - entry.getRed();
            long dg = from.getGreen() - entry.getGreen();
            long db = from.getBlue() - entry.getBlue();
            return (double) (dr * dr + dg * dg + db * db);
        });
    }

    /** Nearest in Oklab, the thing being proposed. */
    private static Color oklab(Color wanted) {
        return MapColors.INSTANCE.color(PERCEPTUAL.index(rounded(wanted)));
    }

    /** The production matcher itself, so this measures what would ship rather than a copy of it. */
    private static final de.flog99.mapgui.ui.PerceptualPalette PERCEPTUAL =
            new de.flog99.mapgui.ui.PerceptualPalette(MapColors.INSTANCE);

    private static Color best(java.util.function.ToDoubleFunction<Color> cost) {
        Color found = null;
        double closest = Double.MAX_VALUE;
        for (byte index : MapColors.INSTANCE.entries()) {
            Color entry = MapColors.INSTANCE.color(index);
            if (entry == null || entry.getAlpha() < 255) continue;

            double at = cost.applyAsDouble(entry);
            if (at < closest) {
                closest = at;
                found = entry;
            }
        }
        return found;
    }

    /**
     * Averaged over the bright range, no matcher is far enough ahead to be worth moving every pixel for.
     *
     * <p>Under one dE between them, on an error whose mean is eighteen. That is the shape of the whole problem:
     * the palette is sparse enough that the choice is usually between two entries which are both a long way from
     * what was wanted, so which of them a formula prefers moves the result far less than the sparseness does.
     *
     * <p>Plain RGB comes out <i>worse</i> than vanilla here, which is worth knowing on its own - vanilla's
     * weighting is a crude perceptual model and crude beats none. It is also why extending the dark table's rule
     * upward was tried and dropped: measured the same way it cost 7% on the bright range and 10% on bright greys.
     *
     * <p>The dark end was a different problem and stayed fixed: its entries are crowded, one of the darkest is
     * warm, and the table's own cells were wider than the gaps between them. That is resolution as much as
     * metric, which no choice of formula addresses.
     */
    @Test
    void noMatcherBeatsVanillaByEnoughToJustifyMovingEveryPixel() {
        Random random = new Random(11);
        double vanillaTotal = 0;
        double rgbTotal = 0;
        double oklabTotal = 0;
        int samples = 20000;

        for (int i = 0; i < samples; i++) {
            Color wanted = new Color(
                    DARK + random.nextInt(256 - DARK),
                    DARK + random.nextInt(256 - DARK),
                    DARK + random.nextInt(256 - DARK));

            vanillaTotal += cielab(wanted, vanilla(wanted));
            rgbTotal += cielab(wanted, plainRgb(wanted));
            oklabTotal += cielab(wanted, oklab(wanted));
        }

        double vanillaMean = vanillaTotal / samples;
        double rgbMean = rgbTotal / samples;
        double oklabMean = oklabTotal / samples;
        System.out.printf("bright range, mean CIELAB dE over %d colours: vanilla %.3f, plain rgb %.3f, oklab %.3f%n",
                samples, vanillaMean, rgbMean, oklabMean);

        // Under one dE is under what anybody can see side by side, let alone on a map at this size. If this ever
        // fails, something changed underneath and the question is worth reopening rather than the test relaxing.
        assertTrue(Math.abs(oklabMean - vanillaMean) < 1.0,
                "vanilla " + vanillaMean + ", oklab " + oklabMean);
        assertTrue(rgbMean > vanillaMean,
                "plain distance is expected to lose to vanilla's weighting up here: rgb " + rgbMean
                        + ", vanilla " + vanillaMean);
    }

    /** CIELAB dE76: the referee. Neither matcher above uses this arithmetic, which is the whole point. */
    private static double cielab(Color from, Color to) {
        double[] one = lab(from);
        double[] two = lab(to);
        double dl = one[0] - two[0];
        double da = one[1] - two[1];
        double db = one[2] - two[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    private static double[] lab(Color color) {
        double r = linear(color.getRed() / 255.0);
        double g = linear(color.getGreen() / 255.0);
        double b = linear(color.getBlue() / 255.0);

        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;

        return new double[] {116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))};
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116.0;
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    /**
     * Where the difference actually lives, since a mean can hide a win worth having - and here it hid two.
     *
     * <p>Measured, and the reason the matcher was left alone:
     *
     * <pre>
     * greys      mean  2.486 -&gt;  2.538 (+2.1%)   p95  5.584 -&gt;  5.896 (+5.6%)
     * saturated  mean 18.776 -&gt; 18.038 (-3.9%)   p95 36.591 -&gt; 34.460 (-5.8%)   worst 66.8 -&gt; 52.3
     * </pre>
     *
     * <p>Oklab is better on saturated colour, clearly so at the tail - its worst case is a fifth better, which is
     * a photograph's brightest patch landing somewhere less wrong. And it is <b>worse on grey</b>, which is what
     * a menu is made of: panels, text, dividers, chrome. Trading the common case for the uncommon one is the
     * wrong way round for a UI library, and the overall figure that averages them hides which way each moved.
     *
     * <p>What that suggests, if this is ever picked up again: split by chroma the way the dark table already
     * splits by it, rather than swapping the metric outright. Worth measuring before believing.
     */
    @Test
    void whereOklabWinsAndByHowMuch() {
        report("greys", 20000, random -> {
            int level = DARK + random.nextInt(256 - DARK);
            int spread = random.nextInt(9) - 4;
            return new Color(Math.clamp(level + spread, 0, 255), level, Math.clamp(level - spread, 0, 255));
        });
        report("saturated", 20000, random -> {
            int high = 160 + random.nextInt(96);
            int low = DARK + random.nextInt(60);
            return switch (random.nextInt(3)) {
                case 0 -> new Color(high, low, low);
                case 1 -> new Color(low, high, low);
                default -> new Color(low, low, high);
            };
        });
        report("anything", 20000, random -> new Color(
                DARK + random.nextInt(256 - DARK),
                DARK + random.nextInt(256 - DARK),
                DARK + random.nextInt(256 - DARK)));
    }

    private void report(String what, int samples, java.util.function.Function<Random, Color> source) {
        Random random = new Random(23);
        double[] vanilla = new double[samples];
        double[] oklab = new double[samples];

        for (int i = 0; i < samples; i++) {
            Color wanted = source.apply(random);
            vanilla[i] = cielab(wanted, vanilla(wanted));
            oklab[i] = cielab(wanted, oklab(wanted));
        }
        java.util.Arrays.sort(vanilla);
        java.util.Arrays.sort(oklab);

        System.out.printf("%-10s vanilla -> oklab:  mean %6.3f -> %6.3f (%+5.1f%%)   p95 %6.3f -> %6.3f (%+5.1f%%)"
                        + "   worst %6.3f -> %6.3f%n",
                what,
                mean(vanilla), mean(oklab), 100 * (mean(oklab) - mean(vanilla)) / mean(vanilla),
                vanilla[(int) (samples * 0.95)], oklab[(int) (samples * 0.95)],
                100 * (oklab[(int) (samples * 0.95)] - vanilla[(int) (samples * 0.95)]) / vanilla[(int) (samples * 0.95)],
                vanilla[samples - 1], oklab[samples - 1]);
    }

    private static double mean(double[] values) {
        double total = 0;
        for (double value : values) total += value;
        return total / values.length;
    }
}
