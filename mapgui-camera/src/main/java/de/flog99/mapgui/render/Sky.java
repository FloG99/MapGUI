package de.flog99.mapgui.render;

/**
 * What a ray that hits nothing looks at: gradient, sun, moon, the glow at dawn and dusk, stars and clouds.
 *
 * <p>The sun's path is a great circle through east, the zenith and west, so it lives in the X-Y plane, and the moon
 * is directly opposite it. Where along that circle is not proportional to the time of day - Minecraft eases the sun
 * through the middle of the day and hurries it across the horizons, so reading the clock as a straight fraction of
 * 24000 puts dawn and dusk visibly wrong. {@link #fractionOfDay} reproduces the client's own easing.
 *
 * <p>Immutable and built once per capture, so every ray can ask it anything without locking.
 */
public final class Sky {

    /** Where vanilla puts the cloud sheet. */
    private static final double CLOUD_HEIGHT = 192;

    private static final double CLOUD_SCALE = 12;

    /**
     * Angular half-width of the sun and the moon, in radians, taken from the quads the client draws rather than
     * chosen: the sun spans 30 either side of centre on a plane 100 away and the moon 20. Both are enormous next to
     * the real thing, and that is what Minecraft's sky looks like - the 3:2 between them is the part to keep.
     */
    private static final double SUN_RADIUS = Math.atan(30.0 / 100.0);

    private static final double MOON_RADIUS = Math.atan(20.0 / 100.0);

    /**
     * The most the night takes off a block's sky light. The client subtracts eleven, leaving open ground at level 4;
     * seven leaves it at 8, for the same reason the renderer lifts the dark end of its light table.
     */
    private static final int MAX_SKY_DARKEN = 7;

    /**
     * What the sky dims to at midnight rather than to black, and a palette decision rather than an astronomical one.
     * The two darkest map colors are TERRACOTTA_BLACK at rgb(19,11,8) and COLOR_BLACK at rgb(13,13,13), and the match
     * weights blue error above red - so a dome dimmed to pure black lands on the terracotta and comes out reddish.
     *
     * <p>Chosen against that table: this quantizes to COLOR_BLACK and {@link #NIGHT_HORIZON} one step lighter, so the
     * dome keeps a faint gradient at night rather than flattening into one shade.
     */
    private static final int NIGHT_ZENITH = 0xFF060810;

    /** The horizon's own night color, one palette step lighter than the zenith. */
    private static final int NIGHT_HORIZON = 0xFF0A0D1A;

    /**
     * What the pale haze at the skyline warms to while the sun is down near it. Separate from the glow band, which
     * sits on the sun's own side and fades with height - above it the sky is a dimmed blue that reads as flat grey,
     * and warming the haze turns that yellowish without painting it orange. The distance fog is mixed from these two.
     */
    private static final int HAZE_WARM = 0xFFFFD8A2;

    /**
     * How much of a direction's blue the glow takes with it, at full strength. Chosen against the map's palette by
     * counting how many sampled directions through the dawn window land on a neutral entry: below four tenths does
     * not clear the mauve, past six tenths pulls the blue sky above the band toward neutral instead.
     */
    private static final float GLOW_BLUE_LOSS = 0.42f;

    /** How much of the pale the horizon takes. */
    private static final float HAZE_SHARE = 0.34f;

    /** Resolved once rather than per ray: every sky pixel above the horizon would otherwise look all three up. */
    private final Texture sunDisc;
    private final Texture moonDisc;
    private final Texture cloudSheet;

    private final boolean celestial;
    private final boolean stars;
    private final boolean clouds;

    private final double sunX;
    private final double sunY;

    /** 0 at night, 1 in full day, easing through dawn and dusk. */
    private final float daylight;

    /** The dawn or dusk band's color, from {@link SunGlow}, and 0 for a sun nowhere near a horizon. */
    private final int glowColor;

    /** Its alpha on its own, since the haze at the skyline warms with the band rather than with the fan's shape. */
    private final float glow;

    /** Which way along x the sun lies, so the band sits on its own side. */
    private final double glowSide;

    private final int zenith;
    private final int horizon;
    private final int fogColor;
    private final int skyDarken;
    private final boolean foggy;
    private final float ambient;

    /** Not a color, so any real one can mean "this sky is a single flat shade". */
    private static final int NOT_FLAT = 0;

    private final int flat;

    /** One shade in every direction, for a caller that wants a plain backdrop rather than a sky. */
    public static Sky flat(int argb) {
        return new Sky(argb | 0xFF000000);
    }

    private Sky(int argb) {
        this.sunDisc = null;
        this.moonDisc = null;
        this.cloudSheet = null;
        this.celestial = false;
        this.stars = false;
        this.clouds = false;
        this.sunX = 1;
        this.sunY = 0;
        this.daylight = 1;
        this.glowColor = 0;
        this.glow = 0;
        this.glowSide = 1;
        this.zenith = argb;
        this.horizon = argb;
        this.fogColor = argb;
        this.skyDarken = 0;
        this.foggy = false;
        this.ambient = 0;
        this.flat = argb;
    }

    /**
     * Which sky is overhead: a dimension rather than a biome. The client derives a sky color from the biome's
     * temperature, but every overworld biome lands within eleven of 255 of every other. The Nether and the End also
     * have no day and no sun, so a clock that dimmed them would invent a night they do not have.
     */
    public enum Dome {

        OVERWORLD(0xFF79A7FF, true, false, 0f),

        /**
         * The dull red that reads as the Nether's fog, for a pack whose biomes state none - and the one dimension
         * whose air hides distance on its own.
         */
        NETHER(0xFF330707, false, true, 0.1f),

        END(0xFF0C0A14, false, false, 0.25f);

        private final int tint;
        private final boolean daylit;
        private final boolean foggy;
        private final float ambient;

        Dome(int tint, boolean daylit, boolean foggy, float ambient) {
            this.tint = tint;
            this.daylit = daylit;
            this.foggy = foggy;
            this.ambient = ambient;
        }
    }

    /**
     * The sun, the moon and the stars are not optional. They are where they are, and a camera with a button to
     * remove them from the sky is a camera with a button nobody wants. Clouds are, only because the client never
     * says whether it is drawing any.
     */
    public Sky(long timeOfDay, int moonPhase, boolean storm, Dome dome, boolean clouds, Textures textures) {
        this(timeOfDay, moonPhase, storm, dome, clouds, textures, 0);
    }

    /**
     * The same, for a dimension whose background is the air rather than a sky.
     *
     * @param air what the fog is where the camera stands, as packed ARGB, or 0 to use the dimension's own. The whole of
     *            what you see in the Nether is fog and its color is the biome's: a crimson forest is dark red, a soul
     *            sand valley teal, basalt deltas grey. One constant for the dimension paints all of them red.
     */
    public Sky(long timeOfDay, int moonPhase, boolean storm, Dome dome, boolean clouds, Textures textures, int air) {
        this.flat = NOT_FLAT;
        // A sun in the Nether would be a window onto a sky that is not there, and the End's own stars are part of
        // its texture rather than a field that turns.
        this.celestial = dome.daylit;
        this.stars = dome.daylit;
        this.clouds = clouds && dome.daylit;

        this.sunDisc = textures.get("environment/celestial/sun");
        this.moonDisc = textures.get(moonTexture(moonPhase));
        this.cloudSheet = textures.get("environment/clouds");

        // Zero is straight up, so the angle measures away from noon rather than from any horizon.
        double angle = fractionOfDay(timeOfDay) * 2 * Math.PI;
        this.sunY = Math.cos(angle);
        this.sunX = -Math.sin(angle);

        // Full day once the sun is a little clear of the horizon, and the ramp either side is dawn and dusk.
        this.daylight = dome.daylit ? (float) Math.clamp((sunY + 0.18) / 0.36, 0, 1) : 1f;

        // The band's own color and strength, both the client's - see SunGlow. Its side is the sun's: sunX is only
        // ever zero with the sun straight overhead or straight under, and neither is a sunset.
        this.glowColor = dome.daylit ? SunGlow.colorAt(sunY) : 0;
        this.glow = (glowColor >>> 24) / 255f;
        this.glowSide = sunX < 0 ? -1 : 1;

        // The client's own curve, which holds full brightness across the middle of the day rather than peaking at
        // noon and falling away from it.
        float lit = dome.daylit ? (float) Math.clamp(Math.cos(angle) * 2 + 0.5, 0, 1) : 1f;
        lit *= storm && dome.daylit ? 0.45f : 1f;

        this.skyDarken = dome.daylit ? Math.min(MAX_SKY_DARKEN, (int) ((1 - lit) * 11)) : 0;
        this.foggy = dome.foggy;
        this.ambient = dome.ambient;

        // Toward the night colors rather than toward black - see NIGHT_ZENITH. A dimension with no day cycle keeps
        // its own tint at full strength.
        int tint = !dome.daylit && air != 0 ? air : dome.tint;
        this.zenith = dome.daylit ? mix(NIGHT_ZENITH, tint, lit) : tint;
        // Paler toward the horizon before the day dims it rather than after, or a night sky comes out grey.
        int haze = dome.daylit ? mix(0xFFFFFFFF, HAZE_WARM, glow) : 0xFFFFFFFF;
        this.horizon = dome.daylit ? mix(NIGHT_HORIZON, mix(tint, haze, HAZE_SHARE), lit) : tint;
        this.fogColor = mix(zenith, horizon, 0.65f);
    }

    /**
     * How far round its circle the sun is, 0 at noon and 0.5 at midnight. The client's easing: a quarter-turn offset
     * so the cosine is measured from noon, then two thirds of a linear ramp blended with one third of a cosine one.
     *
     * <p>26.2 states the same curve as a cubic bezier on its {@code minecraft:visual/sun_angle} track rather than as
     * arithmetic. Held against it across the day the two are within 0.06 degrees of sun angle, so this stays as it
     * is - and package-private, since {@code SunGlowTest} needs it to check the band against the colors the client
     * ships for a tick.
     */
    static double fractionOfDay(long timeOfDay) {
        double turns = Math.floorMod(timeOfDay, 24000L) / 24000.0 - 0.25;
        double fraction = turns - Math.floor(turns);
        double eased = 0.5 - Math.cos(fraction * Math.PI) / 2;
        return (fraction * 2 + eased) / 3;
    }

    /**
     * The light this dimension gives everything for nothing, on the client's own 0 to 1 scale: nothing in the
     * overworld, a tenth in the Nether, a quarter in the End. It matters most where it sounds least important -
     * there is no sky light in either of those, so nearly every surface sits where this lifts hardest.
     */
    public float ambientLight() {
        return ambient;
    }

    /**
     * Whether the air itself hides distance here, whatever the camera was asked for - the client's own
     * {@code isFoggyAt}, which is true in the Nether and nowhere else.
     */
    public boolean foggyAir() {
        return foggy;
    }

    /** A single representative color, for fading distant terrain into. */
    public int fogColor() {
        return fogColor;
    }

    /**
     * How much to take off a block's stored sky light. Sky light is how much of the sky reaches a block and nothing
     * more, so it stays 15 on open ground at midnight - the day cycle is applied when the world is drawn rather than
     * when it is lit, and reading the stored value straight makes midnight as bright as noon.
     */
    public int skyDarken() {
        return skyDarken;
    }

    /**
     * The sky in one direction, as packed ARGB.
     *
     * @param eyeY needed for the clouds, which are a sheet at a fixed height rather than part of the dome
     */
    public int colorFor(double eyeY, double dx, double dy, double dz) {
        if (flat != NOT_FLAT) return flat;

        int color = gradient(dy);

        if (glow > 0.001f) {
            color = addGlow(color, dx, dy, dz);
        }

        if (stars && daylight < 0.9f) {
            color = addStars(color, dx, dy, dz);
        }

        if (celestial) {
            color = addDiscs(color, dx, dy, dz);
        }

        if (clouds && dy > 0.001) {
            color = addClouds(color, eyeY, dx, dy, dz);
        }

        return color;
    }

    /** Lighter toward the horizon, which is what makes a sky read as a dome rather than a flat wash. */
    private int gradient(double dy) {
        float up = (float) Math.clamp(Math.abs(dy), 0, 1);
        return mix(horizon, zenith, (float) Math.pow(up, 0.55));
    }

    /**
     * Dawn and dusk, over the sun's own half of the sky.
     *
     * <p>The band is the client's fan, solved for per ray - {@link SunGlow} has the geometry and where it came from.
     * Here it is only the three components that fan is stated in: along the horizon toward the sun, along the
     * horizon at right angles to that, and up. The sun's circle is the x-y plane, so its own side is x and the one
     * across it is z.
     *
     * <p>Composited as the client composites it, which is a plain alpha blend over the sky behind it: the color is
     * the band's, the alpha is its own times how much of the fan this direction meets.
     */
    private int addGlow(int color, double dx, double dy, double dz) {
        float amount = glow * SunGlow.coverage(dx * glowSide, dz, dy, glow);
        if (amount <= 0.001f) return color;

        return warmed(mix(color, glowColor, amount), amount);
    }

    /**
     * Takes some blue out of a color the glow has been mixed into, in proportion to how much glow it took.
     *
     * <p>Which is the difference between a sunrise and a grey morning, and the reason is the palette rather than the
     * sky. Warm light mixed into a blue sky averages to a mauve with red and blue within a few units of each other -
     * and the nearest of the map's 143 colors to that is a neutral grey, so the band's upper half came out grey while
     * its arithmetic said mauve. Measured at dawn: the sky at 25 degrees up was rgb(162, 148, 163) and drew as
     * rgb(153, 153, 153).
     *
     * <p>Taking blue out rather than adding red, because that is what the sky itself does: light arriving along the
     * skyline has had its blue scattered out of it on the way, which is why the sun goes red at all. It also keeps the
     * band from brightening, which adding red would.
     */
    private static int warmed(int color, float amount) {
        int blue = color & 0xFF;
        int kept = Math.round(blue * (1 - Math.min(1, amount) * GLOW_BLUE_LOSS));

        return color & 0xFFFFFF00 | kept;
    }

    /**
     * Stars, fixed to the celestial sphere so they turn with it through the night.
     *
     * <p>Hashed from a quantized direction rather than stored: a star field is a scattering of points, and one
     * that can be recomputed from its coordinates needs no table and comes out the same every capture.
     */
    private int addStars(int color, double dx, double dy, double dz) {
        if (dy <= 0) return color;

        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Back into celestial space, so the field rotates with the sun rather than being painted on the world.
        double celestialX = (dx * sunY - dy * sunX) / length;
        double celestialY = (dx * sunX + dy * sunY) / length;
        double celestialZ = dz / length;

        int cellX = (int) Math.floor(celestialX * 140);
        int cellY = (int) Math.floor(celestialY * 140);
        int cellZ = (int) Math.floor(celestialZ * 140);

        int hash = cellX * 73856093 ^ cellY * 19349663 ^ cellZ * 83492791;
        hash ^= hash >>> 13;
        if ((hash & 0x3FF) > 5) return color;

        float night = 1 - daylight;
        float brightness = (0.45f + ((hash >>> 11 & 0xFF) / 255f) * 0.55f) * night;
        return mix(color, 0xFFFFFFFF, Math.clamp(brightness, 0, 1));
    }

    /** The sun and the moon, from their own textures, at opposite points of the same circle. */
    private int addDiscs(int color, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double nx = dx / length;
        double ny = dy / length;
        double nz = dz / length;

        int sun = disc(color, nx, ny, nz, sunX, sunY, SUN_RADIUS, sunDisc);
        if (sun != color) return sun;

        return disc(color, nx, ny, nz, -sunX, -sunY, MOON_RADIUS, moonDisc);
    }

    /**
     * One celestial body, if the ray lands on it. Both sit in the X-Y plane, so the disc's own axes are the world Z
     * for one direction and the body's perpendicular within that plane for the other.
     */
    private static int disc(int color, double nx, double ny, double nz, double bodyX, double bodyY, double radius, Texture art) {
        double towardBody = nx * bodyX + ny * bodyY;
        if (towardBody <= 0) return color;

        // Offsets across the disc: one along Z, one along the body's perpendicular inside the X-Y plane.
        double across = nz;
        double down = nx * -bodyY + ny * bodyX;
        if (Math.abs(across) > radius || Math.abs(down) > radius) return color;

        // Both axes flipped: vanilla's celestial quad is built in a frame turned a quarter turn twice over, which
        // puts its texture origin at the opposite corner from the one this arrives at.
        double u = (1 - across / radius) / 2;
        double v = (1 - down / radius) / 2;
        int texel = art.sample((float) (u * 16), (float) (v * 16));
        int alpha = texel >>> 24;
        if (alpha < 8) return color;

        // Additive, because a sun is light rather than a painted object, and it keeps the disc from having a hard
        // dark edge against the sky.
        return add(color, texel, alpha / 255f);
    }

    /** Eight phases, each its own file since 1.21.9 split them out of the old atlas. */
    private static String moonTexture(int moonPhase) {
        return switch (Math.floorMod(moonPhase, 8)) {
            case 0 -> "environment/celestial/moon/full_moon";
            case 1 -> "environment/celestial/moon/waning_gibbous";
            case 2 -> "environment/celestial/moon/third_quarter";
            case 3 -> "environment/celestial/moon/waning_crescent";
            case 4 -> "environment/celestial/moon/new_moon";
            case 5 -> "environment/celestial/moon/waxing_crescent";
            case 6 -> "environment/celestial/moon/first_quarter";
            default -> "environment/celestial/moon/waxing_gibbous";
        };
    }

    /**
     * The cloud sheet, hit where the ray crosses its height.
     *
     * <p>A plane rather than a volume: vanilla's clouds are a flat sheet of quads, and at map resolution the
     * difference is not visible. A ray already below them and heading down never reaches one.
     */
    private int addClouds(int color, double eyeY, double dx, double dy, double dz) {
        if (eyeY >= CLOUD_HEIGHT) return color;

        double distance = (CLOUD_HEIGHT - eyeY) / dy;
        if (distance <= 0 || distance > 8000) return color;

        double atX = dx * distance;
        double atZ = dz * distance;

        float u = (float) (Math.floorMod((long) (atX / CLOUD_SCALE), 256L) / 256.0 * 16);
        float v = (float) (Math.floorMod((long) (atZ / CLOUD_SCALE), 256L) / 256.0 * 16);
        int texel = cloudSheet.sample(u, v);
        int alpha = texel >>> 24;
        if (alpha < 8) return color;

        // Cloud art is white with alpha, so it takes its color from the light of the time of day.
        int lit = mix(0xFF2A3550, 0xFFFFFFFF, daylight);
        float cover = alpha / 255f * 0.85f;
        // Thinner toward the horizon, where a flat sheet would otherwise become an opaque band.
        cover *= (float) Math.clamp(Math.abs(dy) * 4, 0, 1);
        return mix(color, lit, cover);
    }

    private static int mix(int from, int to, float amount) {
        float clamped = Math.clamp(amount, 0, 1);
        int red = Math.round((from >> 16 & 0xFF) * (1 - clamped) + (to >> 16 & 0xFF) * clamped);
        int green = Math.round((from >> 8 & 0xFF) * (1 - clamped) + (to >> 8 & 0xFF) * clamped);
        int blue = Math.round((from & 0xFF) * (1 - clamped) + (to & 0xFF) * clamped);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int add(int base, int light, float amount) {
        int red = Math.min(255, (base >> 16 & 0xFF) + Math.round((light >> 16 & 0xFF) * amount));
        int green = Math.min(255, (base >> 8 & 0xFF) + Math.round((light >> 8 & 0xFF) * amount));
        int blue = Math.min(255, (base & 0xFF) + Math.round((light & 0xFF) * amount));
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }
}
