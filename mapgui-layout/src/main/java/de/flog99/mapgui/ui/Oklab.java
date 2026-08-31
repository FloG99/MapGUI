package de.flog99.mapgui.ui;

import java.awt.Color;

/**
 * sRGB in a space where equal distances look equally different, for judging how wrong a matched colour is.
 *
 * <p>RGB distance is what the palette matches by and it is not what the eye compares by: equal steps in red,
 * green and blue do not look equally far apart, so two colours the same distance apart in RGB can be obviously
 * different and barely different. That is fine as a way to <i>pick</i> an entry, since the palette is fixed and
 * something has to be picked, and useless as a way to <i>score</i> the picking - it would only ever agree with
 * whichever formula it already is.
 *
 * <p>So this exists to be the referee rather than the player. <a href="https://bottosson.github.io/posts/oklab/">
 * Oklab</a> is a perceptual space that is cheap to compute and behaves well on the dark and saturated colours
 * where the older ones do not, which is exactly the corner of the map palette that has caused trouble.
 *
 * <p>Nothing on the drawing path calls this. It is used by the tests that decide whether one matcher is better
 * than another, and it is here rather than in a test source so that the answer to "better by what" is written
 * down in the same place as the code it judges.
 */
public final class Oklab {

    /** A colour as lightness and two opponent axes. Distances between these are what "looks different" means. */
    public record Lab(double lightness, double green, double blue) {
    }

    private Oklab() {
    }

    public static Lab of(Color color) {
        return of(color.getRed(), color.getGreen(), color.getBlue());
    }

    /** From 0 to 255 per channel, as everything here stores colour. */
    public static Lab of(int red, int green, int blue) {
        double r = linear(red / 255.0);
        double g = linear(green / 255.0);
        double b = linear(blue / 255.0);

        double longWave = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double middleWave = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double shortWave = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);

        return new Lab(
                0.2104542553 * longWave + 0.7936177850 * middleWave - 0.0040720468 * shortWave,
                1.9779984951 * longWave - 2.4285922050 * middleWave + 0.4505937099 * shortWave,
                0.0259040371 * longWave + 0.7827717662 * middleWave - 0.8086757660 * shortWave
        );
    }

    /**
     * How different two colours look, on a scale where about 0.02 is the point most people stop seeing a join.
     *
     * <p>Plain Euclidean distance, which is what a perceptual space is for: the work of weighting is in getting
     * there, so nothing is weighted here.
     */
    public static double difference(Color from, Color to) {
        return difference(of(from), of(to));
    }

    public static double difference(Lab from, Lab to) {
        double dl = from.lightness() - to.lightness();
        double dg = from.green() - to.green();
        double db = from.blue() - to.blue();
        return Math.sqrt(dl * dl + dg * dg + db * db);
    }

    /** sRGB's transfer curve undone, since the matrices below want light rather than encoded values. */
    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }
}
