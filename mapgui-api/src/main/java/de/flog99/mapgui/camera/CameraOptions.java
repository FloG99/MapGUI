package de.flog99.mapgui.camera;

/**
 * What a capture should look like.
 *
 * @param width       pixels across, and {@code height} pixels down. {@link Camera#MAP_SIZE} each way fills a map
 *                    exactly; halving them quarters the work and the palette hides much of the loss. Each is at least
 *                    {@link #MIN_SIZE} and the two multiply to at most {@link #MAX_PIXELS}
 * @param fov         vertical degrees. The client's default is 70 and the server cannot see what a player set
 * @param maxDistance blocks to trace, or 0 to follow the viewer's own render distance. Capped by the
 *                    server's view distance either way, since past that nothing is loaded to trace
 * @param fog         fade the far distance toward the sky. Off by default: it is a look rather than a fidelity, and
 *                    the distance cap is far enough out now that it does not need hiding
 * @param entities    draw the mobs and players in view. Off gives a clean landscape
 * @param clouds      the cloud sheet. A setting rather than a reading because the client never sends whether it
 *                    has clouds switched on, or which of the two kinds it is drawing
 * @param selfie      shoot from arm's length in front of the holder, facing back at them. Turned rather than
 *                    mirrored, so the landscape behind them is not flipped
 */
public record CameraOptions(int width, int height, float fov, int maxDistance, boolean fog, boolean entities,
                            boolean clouds, boolean selfie) {

    /** Smaller than this is a few dozen rays and not a picture of anything. */
    public static final int MIN_SIZE = 16;

    /**
     * The most a square capture will trace, which is four maps to a side.
     *
     * <p>A ceiling because the cost of a square frame is the square of it: 512 is a quarter of a million rays a frame
     * and the next step up is a million. Bigger pictures are more captures rather than one enormous one.
     */
    public static final int MAX_SIZE = Camera.MAP_SIZE * 4;

    /**
     * The most pixels a capture may be, however they are arranged.
     *
     * <p>The real ceiling, and stated in pixels rather than per side because that is what a capture costs. A limit on
     * each side is only the right limit for a square frame: one that is <b>long and thin</b> can be far wider than
     * {@link #MAX_SIZE} and still be cheaper than a square one at it. A row of mirrors down a wall is thirteen blocks
     * across and one high, and one frame covering all of them at full resolution is 213 thousand pixels, where a 512
     * square is 262 thousand.
     */
    public static final int MAX_PIXELS = MAX_SIZE * MAX_SIZE;

    /**
     * Refuses a size it cannot honour rather than quietly moving it.
     *
     * <p>This used to clamp, which was worse than it sounds: {@link de.flog99.mapgui.map.MapPrinter} cuts a capture
     * into whole maps, so a size pulled down to 512 stopped being a multiple of 128 and the shot came back
     * unprintable - reported to whoever pressed the shutter as a photograph that failed. A size is a constant in
     * somebody's code, so a wrong one is a bug to hear about once rather than a condition to survive.
     *
     * <p>The other two still clamp, since nothing downstream depends on their exact value.
     */
    public CameraOptions {
        if (width < MIN_SIZE || height < MIN_SIZE) {
            throw new IllegalArgumentException("A capture is at least " + MIN_SIZE + " pixels each way, which "
                    + width + "x" + height + " is not");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("A capture is at most " + MAX_PIXELS + " pixels, which " + width + "x"
                    + height + " (" + (long) width * height + ") is not");
        }
        fov = Math.clamp(fov, 10f, 170f);
        maxDistance = Math.clamp(maxDistance, 0, 512);
    }

    /**
     * The shape this record had before 1.2.0, when a capture was always square.
     *
     * <p>Kept so that code written against that constructor still compiles. Adding {@code height} moved the canonical
     * constructor's arity, and while nothing inside this project called it positionally - {@link #defaults} and
     * {@link #size} are how you build one - a record's canonical constructor is part of the published surface, and
     * quietly taking it away is not something a minor version gets to do.
     *
     * <p>Means exactly what it used to mean: {@code size} each way.
     */
    public CameraOptions(int size, float fov, int maxDistance, boolean fog, boolean entities, boolean clouds,
                         boolean selfie) {

        this(size, size, fov, maxDistance, fog, entities, clouds, selfie);
    }

    public static CameraOptions defaults() {
        return new CameraOptions(Camera.MAP_SIZE, Camera.MAP_SIZE, 70f, 0, false, true, true, false);
    }

    /** A square capture, which is what a viewfinder and a photograph both are. */
    public CameraOptions size(int value) {
        return new CameraOptions(value, value, fov, maxDistance, fog, entities, clouds, selfie);
    }

    /**
     * A capture that is not square, for a picture whose shape is decided by something other than the camera.
     *
     * <p>Two things want this and both are surfaces rather than viewfinders: a <b>wall of maps</b>, which is a rectangle
     * of whatever proportions somebody built, and a <b>row of mirrors</b>, where one frame covering all of them is what
     * lets every one of them show the same moment. Pixels stay square either way, so a frame twice as wide as it is tall
     * wants twice the width - which is already true of a {@link CameraEye#window} the caller states, and is applied to
     * the horizontal edges for a frame described by {@link #fov} alone.
     */
    public CameraOptions size(int across, int down) {
        return new CameraOptions(across, down, fov, maxDistance, fog, entities, clouds, selfie);
    }

    /**
     * The side of this capture, for a caller written when every one of them was square.
     *
     * <p>The width, which is that side for anything square.
     */
    public int size() {
        return width;
    }

    /** How much wider than tall, which is what keeps pixels square when the frame is stated as an angle. */
    public double aspect() {
        return width / (double) height;
    }

    public CameraOptions fov(float value) {
        return new CameraOptions(width, height, value, maxDistance, fog, entities, clouds, selfie);
    }

    public CameraOptions maxDistance(int value) {
        return new CameraOptions(width, height, fov, value, fog, entities, clouds, selfie);
    }

    public CameraOptions fog(boolean value) {
        return new CameraOptions(width, height, fov, maxDistance, value, entities, clouds, selfie);
    }

    public CameraOptions entities(boolean value) {
        return new CameraOptions(width, height, fov, maxDistance, fog, value, clouds, selfie);
    }

    public CameraOptions clouds(boolean value) {
        return new CameraOptions(width, height, fov, maxDistance, fog, entities, value, selfie);
    }

    /** Turns the camera around. Needs {@link #entities} on to be worth anything, since the subject is one. */
    public CameraOptions selfie(boolean value) {
        return new CameraOptions(width, height, fov, maxDistance, fog, entities, clouds, value);
    }
}
