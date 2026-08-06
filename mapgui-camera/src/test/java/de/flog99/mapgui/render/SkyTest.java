package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky, tested for the three things that were actually wrong rather than for the tuning.
 *
 * <p>Deliberately says nothing about how wide the dusk band is, how long it lasts or how orange it gets. Those are
 * numbers somebody picked by looking at captures, and a test that pins them only makes them harder to change.
 */
class SkyTest {

    private static final long NOON = 6000;
    private static final long MIDNIGHT = 18000;
    private static final long DUSK = 12750;

    private static Textures paintedSky() {
        return name -> name.startsWith("environment/celestial/") ? quadrants() : TestWorld.solid(0);
    }

    /**
     * Four distinct colors, one per quadrant, so a hit says which corner of a celestial disc it landed on and a flip
     * of either axis shows up as a different color rather than as a subtle difference.
     */
    private static Texture quadrants() {
        int[] pixels = new int[256];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                pixels[y * 16 + x] = x < 8
                        ? (y < 8 ? 0xFFFF0000 : 0xFF00FF00)
                        : (y < 8 ? 0xFF0000FF : 0xFFFFFF00);
            }
        }
        return new Texture(16, 16, pixels, BakedState.Alpha.CUTOUT, 0xFF808080);
    }

    private static Sky sky(long timeOfDay) {
        return new Sky(timeOfDay, 0, false, Sky.Dome.OVERWORLD, false, paintedSky());
    }

    private static int red(int argb) {
        return argb >> 16 & 0xFF;
    }

    private static int green(int argb) {
        return argb >> 8 & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    /** Whether anything around a ring at this elevation reads warm. */
    private static boolean warmAround(Sky sky, double elevationDegrees) {
        double up = Math.sin(Math.toRadians(elevationDegrees));
        double out = Math.cos(Math.toRadians(elevationDegrees));

        for (int step = 0; step < 48; step++) {
            double azimuth = step / 48.0 * 2 * Math.PI;
            int color = sky.colorFor(70, Math.cos(azimuth) * out, up, Math.sin(azimuth) * out);
            if (red(color) > blue(color) + 20) {
                return true;
            }
        }
        return false;
    }

    /**
     * A midnight sky must not read warm. It used to dim to pure black, which is what the client's own curve says and
     * comes out reddish once quantized: the palette's nearest color to black is TERRACOTTA_BLACK, rgb(19,11,8).
     */
    @Test
    void midnightIsCoolRatherThanWarm() {
        Sky night = sky(MIDNIGHT);

        // Never straight up: at midnight the moon is exactly there, and its texture would answer instead of the sky.
        for (double[] direction : new double[][]{{0, 0.9, 0.5}, {1, 0, 0}, {0, 0.2, 1}, {-1, 0.05, -0.3}}) {
            int color = night.colorFor(70, direction[0], direction[1], direction[2]);
            assertTrue(blue(color) > red(color),
                    "a night sky looking " + java.util.Arrays.toString(direction) + " should be cool, got " + Integer.toHexString(color));
        }
    }

    /** Cool, but still a night - the day cycle has to be doing something. */
    @Test
    void nightIsFarDarkerThanDay() {
        int night = sky(MIDNIGHT).colorFor(70, 0, 0.9, 0.5);
        int day = sky(NOON).colorFor(70, 0, 0.9, 0.5);

        assertTrue(blue(night) * 4 < blue(day), "midnight " + Integer.toHexString(night) + " against noon " + Integer.toHexString(day));
    }

    /** Warm sky belongs to dusk, and belongs near the horizon rather than overhead. */
    @Test
    void duskIsWarmNearTheHorizonAndNotOverhead() {
        Sky dusk = sky(DUSK);

        assertTrue(warmAround(dusk, 3), "dusk should put a warm band along the horizon");
        assertTrue(blue(dusk.colorFor(70, 0, 0.9, 0.5)) >= red(dusk.colorFor(70, 0, 0.9, 0.5)),
                "straight up at dusk is still sky rather than sunset");
    }

    /** And the band must not bleed into the middle of the day, which is the risk in widening its window. */
    @Test
    void broadDaylightHasNoWarmBand() {
        for (long ticks : new long[]{NOON, 8000, 10000}) {
            assertTrue(!warmAround(sky(ticks), 3), "tick " + ticks + " is daytime and should have no warm band");
        }
    }

    /** The Nether and the End have no day, so nothing about the clock may touch them. */
    @Test
    void aDomeWithoutADayDoesNotDim() {
        for (Sky.Dome dome : new Sky.Dome[]{Sky.Dome.NETHER, Sky.Dome.END}) {
            int noon = new Sky(NOON, 0, false, dome, false, paintedSky()).colorFor(70, 0, 0.9, 0.5);
            int midnight = new Sky(MIDNIGHT, 0, false, dome, false, paintedSky()).colorFor(70, 0, 0.9, 0.5);

            assertEquals(noon, midnight, dome + " has no day cycle to follow");
        }
    }

    /**
     * Which way round the moon's texture is, on both axes.
     *
     * <p>Both are flipped relative to the frame the ray arrives in, and getting one of the two wrong mirrors the moon
     * rather than turning it - which on a gibbous phase looks almost right, so each axis needs its own answer.
     *
     * <p>Tested on the moon at midnight rather than the sun at noon, and that is not incidental: a disc is drawn
     * additively over the sky, so against a daylit blue the blue channel is already 255 and half the quadrants become
     * indistinguishable. A first version of this test passed while asserting nothing.
     */
    @Test
    void theMoonsTextureIsTurnedRatherThanMirrored() {
        Sky night = sky(MIDNIGHT);

        // The moon at midnight is overhead. Stepping along Z crosses one of the disc's axes and X the other.
        int positiveZ = night.colorFor(70, 0, 1, 0.10);
        int negativeZ = night.colorFor(70, 0, 1, -0.10);
        assertTrue(green(positiveZ) > 200 && red(positiveZ) < 120,
                "+Z should land on the texture's left half, got " + Integer.toHexString(positiveZ));
        assertTrue(red(negativeZ) > 200 && green(negativeZ) > 200,
                "-Z should land on its right half, got " + Integer.toHexString(negativeZ));

        // The small Z offset keeps both of these well inside one half of the first axis.
        int positiveX = night.colorFor(70, 0.10, 1, 0.06);
        int negativeX = night.colorFor(70, -0.10, 1, 0.06);
        assertTrue(green(positiveX) > 200 && red(positiveX) < 120,
                "+X should land on the texture's lower half, got " + Integer.toHexString(positiveX));
        assertTrue(red(negativeX) > 200 && green(negativeX) < 120,
                "-X should land on its upper half, got " + Integer.toHexString(negativeX));
    }
}
