package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band held against the client's own numbers rather than against a screenshot.
 *
 * <p>The colors below are copied out of {@code data/minecraft/timeline/day.json} in the 26.2 client jar - the whole
 * of its {@code minecraft:visual/sunrise_sunset_color} track, tick and ARGB, all 32 keyframes. That track is what
 * the client draws the dusk band in, so reproducing it is the whole job, and a table of what it says at 32 known
 * moments is a test that cannot drift into agreeing with whatever {@link SunGlow} happens to do.
 *
 * <p>It goes through {@link Sky#fractionOfDay} on the way, so this covers the tick to sun angle easing too - a
 * wrong easing lands the peak of the band at the wrong minute of the evening and nothing else here would notice.
 */
class SunGlowTest {

    /**
     * Tick, then ARGB. Verbatim.
     *
     * <p>Blue is 0x33 in every one of the 32, which is the tell that these were baked from arithmetic rather than
     * painted: the client's own form has blue as a flat fifth and only red, green and the alpha move.
     */
    private static final long[] KEYFRAMES = {
            71, 0x5fefa333L, 310, 0x29f5ba33L, 565, 0x06fbd433L, 730, 0x00ffe533L,
            11270, 0x00ffe533L, 11397, 0x04fcd833L, 11522, 0x0ff9cb33L, 11690, 0x29f5ba33L,
            11929, 0x5fefa333L, 12243, 0xb1e78733L, 12358, 0xcce47e33L, 12512, 0xe9e07233L,
            12613, 0xf6dd6b33L, 12732, 0xfeda6333L, 12841, 0xfed75c33L, 13035, 0xecd25133L,
            13252, 0xc1cc4733L, 13775, 0x36be3733L, 13888, 0x1fbb3533L, 14039, 0x09b73333L,
            14192, 0x00b33333L, 21807, 0x00b23333L, 21961, 0x09b73333L, 22112, 0x1fbb3533L,
            22225, 0x36be3733L, 22748, 0xc1cc4733L, 22965, 0xecd25133L, 23159, 0xfed75c33L,
            23272, 0xfeda6333L, 23488, 0xe9e07233L, 23642, 0xcce47e33L, 23757, 0xb1e78733L
    };

    /**
     * How far off a channel may be, and one is as tight as it can be stated: a keyframe is the curve rounded to a
     * byte, so agreeing to the unit is agreeing exactly.
     *
     * <p>Nothing here is a fitted constant, which is why that holds. The two sides differ only in that 26.2 states
     * the tick-to-sun-angle easing as a cubic bezier and {@link Sky} states it as the arithmetic it replaced, and
     * across the day those are within 0.06 degrees of sun angle.
     */
    private static final int TOLERANCE = 1;

    /** What the sun's height is at a tick, which is what the band's color is a function of. */
    private static double sunUpAt(long tick) {
        return Math.cos(Sky.fractionOfDay(tick) * 2 * Math.PI);
    }

    @Test
    void theColorIsTheOneTheClientShipsForThatTick() {
        for (int i = 0; i < KEYFRAMES.length; i += 2) {
            long tick = KEYFRAMES[i];
            int want = (int) KEYFRAMES[i + 1];
            int got = SunGlow.colorAt(sunUpAt(tick));

            int wantedAlpha = want >>> 24;
            assertTrue(Math.abs(wantedAlpha - (got >>> 24)) <= TOLERANCE,
                    "at tick " + tick + ": wanted alpha " + wantedAlpha + ", drew " + (got >>> 24));

            // The five keyframes at the ends of the window state a color and no alpha at all. Nothing is drawn
            // either way, and this says so rather than holding the arithmetic to a color that cannot be seen.
            if (wantedAlpha == 0) continue;

            for (int shift = 0; shift <= 16; shift += 8) {
                int wanted = want >> shift & 0xFF;
                int drawn = got >> shift & 0xFF;
                assertTrue(Math.abs(wanted - drawn) <= TOLERANCE,
                        "at tick " + tick + " channel " + shift + ": wanted " + wanted + ", drew " + drawn);
            }
        }
    }

    /** Outside the client's window there is no band at all, rather than a very faint one. */
    @Test
    void thereIsNoBandAwayFromTheHorizons() {
        assertEquals(0, SunGlow.colorAt(1));
        assertEquals(0, SunGlow.colorAt(-1));
        assertEquals(0, SunGlow.colorAt(0.41));
        assertEquals(0, SunGlow.colorAt(-0.41));
        assertTrue(SunGlow.colorAt(0.39) != 0);
    }

    /** Full strength where the sun goes down, which is the fan's apex. */
    @Test
    void theBandIsStrongestAtTheSunsOwnPointOnTheHorizon() {
        assertEquals(1, SunGlow.coverage(1, 0, 0, 1), 1e-6);
    }

    /**
     * And gone a quarter turn round, which is the half way point between the sun and the moon - the thing a hand
     * rolled falloff gets wrong, since there is nothing about that direction to suggest an edge.
     */
    @Test
    void theBandReachesExactlyAQuarterTurnRound() {
        assertEquals(0, SunGlow.coverage(0, 1, 0, 1), 1e-6);
        // Just inside it there is still something, so the edge is where the fan ends rather than early.
        assertTrue(SunGlow.coverage(Math.cos(Math.toRadians(80)), Math.sin(Math.toRadians(80)), 0, 1) > 0.01f);
        // And past it there is nothing, on the moon's own half of the sky.
        assertEquals(0, SunGlow.coverage(-0.2, 1, 0, 1), 1e-6);
    }

    /**
     * The top of the band is {@code atan(alpha / 3)} up, which is the rim's lift over its radius - about 18 degrees
     * at the height of a sunset and lower as it fades, since the fan flattens with its own alpha.
     */
    @Test
    void theBandStopsWhereTheFansRimDoes() {
        for (float alpha : new float[]{1f, 0.5f, 0.2f}) {
            double edge = Math.atan(alpha / 3);
            assertEquals(0, SunGlow.coverage(Math.cos(edge), 0, Math.sin(edge), alpha), 1e-6,
                    "at alpha " + alpha);
            assertTrue(SunGlow.coverage(Math.cos(edge * 0.9), 0, Math.sin(edge * 0.9), alpha) > 0.01f,
                    "at alpha " + alpha);
            assertEquals(0, SunGlow.coverage(Math.cos(edge * 1.1), 0, Math.sin(edge * 1.1), alpha), 1e-6,
                    "at alpha " + alpha);
        }
    }

    @Test
    void thereIsNoBandOverhead() {
        assertEquals(0, SunGlow.coverage(0, 0, 1, 1), 1e-6);
    }

    /**
     * Under the skyline there is no rim to stop it, so the band hangs far deeper than it rises - which is what a
     * sunrise looks like and what a falloff symmetric about the horizon gets wrong.
     *
     * <p>Straight down is where the sheet passes under the eye, on its way from the apex 100 out on one side to the
     * far rim 120 out on the other: 100 of those 220, so {@code 5/11} of the way from apex to rim, leaving
     * {@code 6/11} of the colour. The lift cancels out of that ratio, so it holds at any alpha.
     */
    @Test
    void theBandHangsBelowTheHorizonRatherThanMirroring() {
        for (float alpha : new float[]{1f, 0.5f, 0.2f}) {
            assertEquals(6 / 11.0, SunGlow.coverage(0, 0, -1, alpha), 1e-6, "at alpha " + alpha);
        }

        for (int degrees = 5; degrees <= 60; degrees += 5) {
            double toward = Math.cos(Math.toRadians(degrees));
            double up = Math.sin(Math.toRadians(degrees));
            assertTrue(SunGlow.coverage(toward, 0, -up, 1) > SunGlow.coverage(toward, 0, up, 1),
                    "not deeper than it is tall at " + degrees + " degrees");
        }

        // The one the eye actually reads, at the height the band stops above: nothing there, and seven tenths of it
        // still hanging the same angle below.
        double edge = Math.atan(1 / 3.0);
        assertEquals(0, SunGlow.coverage(Math.cos(edge), 0, Math.sin(edge), 1), 1e-6);
        assertEquals(0.71, SunGlow.coverage(Math.cos(edge), 0, -Math.sin(edge), 1), 0.01);
    }

    /** Symmetric across the sun's own line, and independent of how long the direction is. */
    @Test
    void theSameDirectionIsTheSameBand() {
        double toward = Math.cos(Math.toRadians(30));
        double side = Math.sin(Math.toRadians(30));

        float once = SunGlow.coverage(toward, side, 0.05, 1);
        assertEquals(once, SunGlow.coverage(toward, -side, 0.05, 1), 1e-6);
        assertEquals(once, SunGlow.coverage(toward * 7, side * 7, 0.35, 1), 1e-6);
    }

    /** Fading upward and round, with no step in either, which is what a band rather than a disc of orange means. */
    @Test
    void itFadesRatherThanStopping() {
        float last = 1.1f;
        for (int degrees = 0; degrees <= 18; degrees++) {
            float here = SunGlow.coverage(Math.cos(Math.toRadians(degrees)), 0, Math.sin(Math.toRadians(degrees)), 1);
            assertTrue(here < last, "not falling at " + degrees + " degrees up");
            last = here;
        }

        last = 1.1f;
        for (int degrees = 0; degrees <= 90; degrees += 5) {
            float here = SunGlow.coverage(Math.cos(Math.toRadians(degrees)), Math.sin(Math.toRadians(degrees)), 0, 1);
            assertTrue(here < last, "not falling at " + degrees + " degrees round");
            last = here;
        }
    }
}
