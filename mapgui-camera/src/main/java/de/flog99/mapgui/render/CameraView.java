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
                         boolean fog, ClipPlane clip, Lens window, boolean[] wanted) {

    /** What the client ships with, and the only sensible guess for a value the server cannot see. */
    public static final float DEFAULT_FOV = 70f;

    public CameraView {
        fov = Math.clamp(fov, 10f, 170f);
        maxDistance = Math.max(1, maxDistance);
    }

    public CameraView(double x, double y, double z, float yaw, float pitch, float fov, int maxDistance) {
        this(x, y, z, yaw, pitch, fov, maxDistance, false, null, null, null);
    }

    public CameraView(double x, double y, double z, float yaw, float pitch, float fov, int maxDistance, boolean fog) {
        this(x, y, z, yaw, pitch, fov, maxDistance, fog, null, null, null);
    }

    public CameraView(double x, double y, double z, float yaw, float pitch, float fov, int maxDistance, boolean fog,
                      ClipPlane clip) {
        this(x, y, z, yaw, pitch, fov, maxDistance, fog, clip, null, null);
    }

    /**
     * How wide the frame is, as the four edges of the picture at one block along the forward axis.
     *
     * <p>A field of view can only describe a frame centred on where the camera points, and there is one thing that
     * genuinely needs otherwise: a <b>mirror</b>. A reflection looks straight out of the glass rather than at the mirror,
     * because that is what lets two mirrors on one wall agree - so a mirror the viewer is not squarely in front of sits
     * off to one side of the frame. Described by an angle, the frame then has to be widened until it reaches the far
     * corner, and everything the widening adds is room the mirror does not show and pixels it does not get. A mirror at a
     * normal mounting height, seen by somebody standing at it, was getting about a third of the frame.
     *
     * <p>Four edges instead of one angle also removes both ends of the fov clamp, which a reflection ran into at each:
     * a small mirror across the room needs a frame narrower than ten degrees, and standing against a tall one needs
     * wider than a hundred and seventy.
     *
     * <p>Tangents rather than angles, since that is what a ray is built from: a pixel looks along
     * {@code forward + right * sx + up * sy} for {@code sx} between {@link #left} and {@link #right} and {@code sy}
     * between {@link #bottom} and {@link #top}.
     *
     * @param symmetric whether this is a plain field of view after all, in which case the arithmetic below takes the
     *                  path it always took - to the last bit, which is what keeps every existing capture identical
     */
    public record Lens(double left, double right, double bottom, double top, boolean symmetric) {

        /** The frame a field of view describes: centred, and as tall as it is wide. */
        public static Lens of(float fov) {
            double tanHalf = Math.tan(Math.toRadians(fov) / 2);
            return new Lens(-tanHalf, tanHalf, -tanHalf, tanHalf, true);
        }

        /** A frame that need not be centred on anything. */
        public static Lens of(double left, double right, double bottom, double top) {
            return new Lens(left, right, bottom, top, false);
        }

        /** Only meaningful when {@link #symmetric}, where all four edges are this far out. */
        public double tanHalf() {
            return top;
        }
    }

    /** The frame this view draws: the one it was given, or the one its field of view describes. */
    public Lens lens() {
        return window != null ? window : Lens.of(fov);
    }

    /**
     * Whether this pixel is wanted at all, for a frame that is not going to be shown as a square.
     *
     * <p>Every ray is independent, so a pixel nobody will look at is work that can simply not be done - a round mirror's
     * glass is a circle in a square, and the frame around it is drawn rather than photographed. Costs one array read on
     * a path that already reads several, and nothing at all when there is no mask.
     *
     * <p>{@code wanted} is one entry per pixel row by row, and is read from every tracing thread at once, so nothing
     * here writes to it.
     */
    public boolean wants(int px, int py, int width) {
        return wanted == null || wanted[py * width + px];
    }

    /**
     * A half-space the frame is confined to, for an eye that is somewhere a ray could not have started from.
     *
     * <p>A mirror is the case. Its camera sits as far behind the glass as the viewer is in front of it, so without
     * this every ray would begin inside the wall the mirror hangs on and the reflection would be solid stone.
     *
     * <p>Nothing behind it exists as far as a frame is concerned: rays start at the crossing, and
     * {@code EntityCapture} drops whatever stands on the far side before a mesh is ever built for it.
     *
     * @param normalX which side is kept, as a unit vector into the half-space that is drawn
     */
    public record ClipPlane(double x, double y, double z, double normalX, double normalY, double normalZ) {

        /** Enough to keep a surface lying exactly on the plane from being hit by the ray that starts there. */
        private static final double SKIN = 1e-4;

        /**
         * How far along a unit direction a ray from {@code (eyeX, eyeY, eyeZ)} enters the half-space, or
         * {@link Double#POSITIVE_INFINITY} if it never does.
         *
         * <p>Written for an eye <b>behind</b> the plane, which is the only place a clip is asked for: a reflection's
         * camera is as far back of the glass as the viewer is in front of it. From there a ray either turns toward the
         * kept side and crosses once, or it does not and there is nothing of it to draw.
         */
        public double entry(double eyeX, double eyeY, double eyeZ, double dx, double dy, double dz) {
            double above = (eyeX - x) * normalX + (eyeY - y) * normalY + (eyeZ - z) * normalZ;
            if (above >= 0) return 0;

            // Parallel to the plane, or heading further behind it. Either way the crossing is not ahead of this ray,
            // and returning where the line meets the plane would start the walk behind the eye and aimed at the wall.
            double toward = dx * normalX + dy * normalY + dz * normalZ;
            if (toward <= 1e-12) return Double.POSITIVE_INFINITY;

            return -above / toward + SKIN;
        }

        public boolean keeps(double pointX, double pointY, double pointZ) {
            return (pointX - x) * normalX + (pointY - y) * normalY + (pointZ - z) * normalZ >= 0;
        }
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
        private final Lens lens;
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
            this.lens = view.lens();
            this.tanHalf = lens.tanHalf();
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
            double sx;
            double sy;
            if (lens.symmetric()) {
                // The expression this has always used, kept for the case that is every capture but a reflection. The
                // general form below is the same arithmetic rearranged, which is not the same in its last bits - and
                // several tests here hold frames to being byte-identical.
                sx = (2.0 * (px + 0.5) / width - 1.0) * tanHalf;
                sy = (1.0 - 2.0 * (py + 0.5) / height) * tanHalf;
            } else {
                sx = lens.left() + (lens.right() - lens.left()) * (px + 0.5) / width;
                sy = lens.top() - (lens.top() - lens.bottom()) * (py + 0.5) / height;
            }

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
