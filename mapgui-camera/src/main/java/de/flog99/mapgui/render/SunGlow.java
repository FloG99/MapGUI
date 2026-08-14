package de.flog99.mapgui.render;

/**
 * The band of colour at dawn and dusk, as the client draws it: its colour, and how much of it any one direction
 * takes.
 *
 * <p>Both halves are the client's own rather than chosen here, because the shape of this is not something an eye
 * can tune from a screenshot - the band is widest along the horizon and reaches barely twenty degrees up, and any
 * falloff written to look right at the sun's own azimuth is wrong ninety degrees round from it.
 *
 * <h2>The colour</h2>
 *
 * <p>26.2 ships it as data - {@code data/minecraft/timeline/day.json}, track
 * {@code minecraft:visual/sunrise_sunset_color}, 32 keyframes of ARGB against the tick. Those keyframes are a bake
 * of the same closed form the client has had for years, which is what is reproduced here: every one of the 32 comes
 * back to within 1 of 255, and {@code SunGlowTest} holds it against all of them.
 *
 * <p>The form rather than the table because the table is a sampling of it - keyframes 100 to 300 ticks apart with a
 * linear ease between, against a curve that is exact everywhere - and because the table is 32 colours to copy by
 * hand and get subtly wrong. Blue is a flat {@code 0x33} in all 32, which is the giveaway.
 *
 * <h2>The shape</h2>
 *
 * <p>A triangle fan, from {@code SkyRenderer.buildSunriseFan}: an apex 100 units away at the horizon on the sun's
 * own side, and a rim of radius 120 that runs the whole way round the camera, lifted {@code 40 * alpha} on the
 * sun's side and dipped the same on the far side. Full colour at the apex, none at the rim, interpolated across.
 *
 * <p>So the band covers <b>the entire half of the sky the sun is on</b>, fading to nothing at ninety degrees round
 * rather than stopping before it, and it reaches {@code atan(alpha / 3)} above the horizon - about 18 degrees at
 * the peak of a sunset, and less as the glow fades, since the lift scales with the alpha.
 *
 * <p><b>It is not symmetric about the horizon, and that is most of what it looks like.</b> Above, it stops at that
 * rim. Below, there is no rim in the way: the sheet carries on from the apex down and under the camera to the far
 * side, so the colour hangs a long way under the skyline. Eighteen degrees over the sun it is already nothing;
 * eighteen degrees under, it is still seven tenths, and straight down it is {@code 6/11} of it whatever the alpha,
 * which is where that sheet passes beneath the eye.
 *
 * <p>Not a detail of the geometry that never shows, either. The client's dark disc - the one thing that would hide
 * it - is drawn only while the eye is <i>below</i> the world's horizon height, so anybody standing on the surface
 * at sunrise is looking at the underside of this whenever the world does not cover it: over an ocean, off a cliff,
 * and along the far edge of what a capture reaches, which is exactly where a photograph shows sky low in the frame.
 *
 * <p>Traced rather than rasterized, which is the one place this leaves the client behind on purpose. On screen the
 * band is not quite a property of the sky: turn toward the moon and the orange in the middle of the view can drop
 * out, so the same sky at the same moment draws differently depending on where the camera is pointed. The geometry
 * says why that is possible at all - the fan is enormous, nearly flat, and the camera sits all but exactly in its
 * plane, which is the arrangement a near plane cuts through. A photograph cannot have that: solving for the surface
 * along each ray asks where the band is rather than where it lands on a screen, so a capture of one sky is one sky
 * whichever way the camera was turned.
 *
 * <p>The rim here is the circle the client's sixteen segments approximate. A chord of a sixteenth sits 1.9% inside
 * its arc, which moves the top of the band by a third of a degree.
 */
public final class SunGlow {

    /**
     * How near the horizon the sun has to be, as the cosine of its angle from straight up.
     *
     * <p>The client's own {@code -0.4 <= cos <= 0.4}, which is a little under 3 minutes of a 20 minute day at each
     * end.
     */
    private static final double WINDOW = 0.4;

    /** Below this the client draws nothing at all, so neither does this. */
    private static final float FAINTEST = 0.001f;

    /** How far away the fan's apex sits, along the horizon on the sun's own side. */
    private static final double APEX = 100;

    /** And the radius of the rim that runs round the camera. */
    private static final double RIM = 120;

    /** How far the rim lifts on the sun's side and dips on the far one, before the alpha scales it. */
    private static final double RISE = 40;

    /** {@link #RIM} over {@link #RISE}: how much steeper the band's edge is than the rim is wide. */
    private static final double SLOPE = RIM / RISE;

    /** And {@link #APEX} over {@link #RIM}, which is where the apex sits along the way out to the rim. */
    private static final double REACH = APEX / RIM;

    private SunGlow() {
    }

    /**
     * The colour of the band right now, as packed ARGB, or 0 while the sun is nowhere near a horizon.
     *
     * <p>Opaque black cannot come out of the arithmetic - blue is a fixed fifth and red never drops below seven
     * tenths - so zero is free to mean "no band today".
     *
     * @param sunUp the cosine of the sun's angle from straight up: 1 at noon, 0 at either horizon, -1 at midnight
     */
    public static int colorAt(double sunUp) {
        if (sunUp < -WINDOW || sunUp > WINDOW) return 0;

        // 0 at the night end of the window, 1 at the day end, and a half with the sun on the horizon.
        double share = sunUp / WINDOW * 0.5 + 0.5;

        // Squared, which is what makes the band brief: it is already past half strength a third of the way out.
        double alpha = 1 - (1 - Math.sin(share * Math.PI)) * 0.99;
        alpha *= alpha;

        return channel(alpha) << 24 | channel(share * 0.3 + 0.7) << 16
                | channel(share * share * 0.7 + 0.2) << 8 | channel(0.2);
    }

    private static int channel(double value) {
        return (int) (Math.clamp(value, 0, 1) * 255 + 0.5);
    }

    /**
     * How much of that colour one direction takes, 0 to 1, before the colour's own alpha.
     *
     * <p>Where the ray meets the fan, as the share of the way from the apex to the rim - and the fan is a ruled
     * surface between the two, so that solves in closed form with no trigonometry and no stepping round its
     * sixteen segments. The direction does not have to be a unit vector: every term below scales with its length
     * and the ratio does not.
     *
     * @param towardSun how much of the direction points along the horizon toward the sun, and {@code sideways}
     *                  how much points along the horizon at right angles to that - which way round is immaterial,
     *                  since the fan is symmetric about the sun's own line and only the square of it is used
     * @param up        how much points at the sky, and negative for a direction below the horizon, which is not the
     *                  same case mirrored - see the class comment
     * @param alpha     the band's own alpha, from {@link #colorAt}, which the fan's lift scales with
     */
    public static float coverage(double towardSun, double sideways, double up, float alpha) {
        if (alpha < FAINTEST) return 0;

        // Distance out to the rim in the plane the ray leaves the apex through, in rim radii. The lift is what
        // divides the height: a band that lifts less has to be looked at from nearer the horizon to be met at all.
        double outward = Math.sqrt(sideways * sideways + SLOPE * SLOPE * up * up / (alpha * alpha));
        double along = towardSun - SLOPE * up / alpha + REACH * outward;
        if (along <= 0) return 0;

        // The share of the way out to the rim, where the colour has run out.
        double share = REACH * outward / along;
        return share >= 1 ? 0 : (float) (1 - share);
    }
}
