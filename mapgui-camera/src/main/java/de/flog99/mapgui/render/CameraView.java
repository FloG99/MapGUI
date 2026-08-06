package de.flog99.mapgui.render;

/**
 * Where the camera is, where it looks, and how wide.
 *
 * <p>The direction convention is Bukkit's own, so that a capture matches what the player is actually looking
 * at rather than something a degree off: yaw 0 faces south down +Z, yaw increases clockwise from above, and
 * positive pitch looks down.
 *
 * <p>{@code fov} is vertical and defaults to 70 because that is the client's default - and the server has no
 * way to know what a given player has set theirs to, so it cannot be matched exactly. A map is square, so the
 * same angle applies across as down.
 */
public record CameraView(double x, double y, double z, float yaw, float pitch, float fov, int maxDistance,
                         boolean fog) {

    /** What the client ships with, and the only sensible guess for a value the server cannot see. */
    public static final float DEFAULT_FOV = 70f;

    public CameraView {
        fov = Math.clamp(fov, 10f, 170f);
        maxDistance = Math.max(1, maxDistance);
    }

    public CameraView(double x, double y, double z, float yaw, float pitch, float fov, int maxDistance) {
        this(x, y, z, yaw, pitch, fov, maxDistance, false);
    }

    /**
     * The camera's orientation worked out once, so that a ray costs arithmetic and nothing else.
     *
     * <p>Worth its own type. The basis and the half-extent are the same for every pixel of a frame, and deriving
     * them per ray meant four trigonometric calls, a square root and three array allocations sixteen thousand times
     * over - to arrive at the same nine numbers each time.
     */
    public static final class Frame {

        private final double forwardX;
        private final double forwardY;
        private final double forwardZ;
        private final double rightX;
        private final double rightZ;
        private final double upX;
        private final double upY;
        private final double upZ;
        private final double tanHalf;

        private Frame(CameraView view) {
            double[] forward = new double[3];
            double[] right = new double[3];
            double[] up = new double[3];
            view.basis(forward, right, up);

            this.forwardX = forward[0];
            this.forwardY = forward[1];
            this.forwardZ = forward[2];
            // Screen-right is horizontal by construction, so its Y is always zero and not worth carrying.
            this.rightX = right[0];
            this.rightZ = right[2];
            this.upX = up[0];
            this.upY = up[1];
            this.upZ = up[2];
            this.tanHalf = Math.tan(Math.toRadians(view.fov()) / 2);
        }

        /**
         * The direction a pixel looks, written into {@code out} as a unit vector.
         *
         * <p>Pixel centers rather than corners, so a 1x1 render looks exactly where the player does instead of half
         * a pixel off up and left.
         *
         * @param out length 3, reused across pixels
         */
        public void direction(int px, int py, int width, int height, double[] out) {
            double sx = (2.0 * (px + 0.5) / width - 1.0) * tanHalf;
            double sy = (1.0 - 2.0 * (py + 0.5) / height) * tanHalf;

            double dx = forwardX + rightX * sx + upX * sy;
            double dy = forwardY + upY * sy;
            double dz = forwardZ + rightZ * sx + upZ * sy;

            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            out[0] = dx / length;
            out[1] = dy / length;
            out[2] = dz / length;
        }
    }

    /** Immutable, so a frame is safe to share across the threads tracing bands of it. */
    public Frame frame() {
        return new Frame(this);
    }

    /** One pixel's direction for a caller that only wants a few, and would rather not hold a {@link Frame}. */
    public void direction(int px, int py, int width, int height, double[] out) {
        frame().direction(px, py, width, height, out);
    }

    /**
     * Forward, screen-right and screen-up.
     *
     * <p>Right is {@code forward x worldUp}, which for a camera facing south comes out as west - and west is
     * indeed on your right when you face south, since the compass runs north, east, south, west clockwise.
     * Getting this backwards mirrors the whole frame, which looks plausible until you read a sign in it.
     */
    public void basis(double[] forward, double[] right, double[] up) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRadians);

        forward[0] = -Math.sin(yawRadians) * cosPitch;
        forward[1] = -Math.sin(pitchRadians);
        forward[2] = Math.cos(yawRadians) * cosPitch;

        // forward x (0,1,0), normalized by dividing out cos(pitch). Degenerate only when looking straight up or
        // down, where any horizontal right is as good as another - and the fallback below is the limit this
        // expression approaches, so the two agree either side of the switch.
        double horizontal = Math.sqrt(forward[0] * forward[0] + forward[2] * forward[2]);
        if (horizontal < 1e-9) {
            right[0] = -Math.cos(yawRadians);
            right[1] = 0;
            right[2] = -Math.sin(yawRadians);
        } else {
            right[0] = -forward[2] / horizontal;
            right[1] = 0;
            right[2] = forward[0] / horizontal;
        }

        // right x forward, both already unit and perpendicular.
        up[0] = right[1] * forward[2] - right[2] * forward[1];
        up[1] = right[2] * forward[0] - right[0] * forward[2];
        up[2] = right[0] * forward[1] - right[1] * forward[0];
    }
}
