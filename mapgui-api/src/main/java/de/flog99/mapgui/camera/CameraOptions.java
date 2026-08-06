package de.flog99.mapgui.camera;

/**
 * What a capture should look like.
 *
 * @param size        pixels square. {@link Camera#MAP_SIZE} fills a map exactly; halving it quarters the work
 *                    and the palette hides much of the loss
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

    public CameraOptions {
        size = Math.clamp(size, 16, 512);
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
