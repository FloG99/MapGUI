package de.flog99.mapgui.camera;

/**
 * What a capture should look like.
 *
 * @param size        pixels square, between {@link #MIN_SIZE} and {@link #MAX_SIZE}. {@link Camera#MAP_SIZE} fills a
 *                    map exactly; halving it quarters the work and the palette hides much of the loss
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
public record CameraOptions(int size, float fov, int maxDistance, boolean fog, boolean entities,
                            boolean clouds, boolean selfie) {

    /** Smaller than this is a few dozen rays and not a picture of anything. */
    public static final int MIN_SIZE = 16;

    /**
     * The most a capture will trace, which is four maps to a side.
     *
     * <p>A ceiling because the cost is the square of it: 512 is a quarter of a million rays a frame and the next step
     * up is a million. Bigger pictures are more captures rather than one enormous one.
     */
    public static final int MAX_SIZE = Camera.MAP_SIZE * 4;

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
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("A capture is between " + MIN_SIZE + " and " + MAX_SIZE
                    + " pixels square, which " + size + " is not");
        }
        fov = Math.clamp(fov, 10f, 170f);
        maxDistance = Math.clamp(maxDistance, 0, 512);
    }

    public static CameraOptions defaults() {
        return new CameraOptions(Camera.MAP_SIZE, 70f, 0, false, true, true, false);
    }

    public CameraOptions size(int value) {
        return new CameraOptions(value, fov, maxDistance, fog, entities, clouds, selfie);
    }

    public CameraOptions fov(float value) {
        return new CameraOptions(size, value, maxDistance, fog, entities, clouds, selfie);
    }

    public CameraOptions maxDistance(int value) {
        return new CameraOptions(size, fov, value, fog, entities, clouds, selfie);
    }

    public CameraOptions fog(boolean value) {
        return new CameraOptions(size, fov, maxDistance, value, entities, clouds, selfie);
    }

    public CameraOptions entities(boolean value) {
        return new CameraOptions(size, fov, maxDistance, fog, value, clouds, selfie);
    }

    public CameraOptions clouds(boolean value) {
        return new CameraOptions(size, fov, maxDistance, fog, entities, value, selfie);
    }

    /** Turns the camera around. Needs {@link #entities} on to be worth anything, since the subject is one. */
    public CameraOptions selfie(boolean value) {
        return new CameraOptions(size, fov, maxDistance, fog, entities, clouds, value);
    }
}
