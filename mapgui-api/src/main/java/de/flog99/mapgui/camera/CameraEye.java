package de.flog99.mapgui.camera;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/**
 * Where a capture is taken from, when that is not out of a player's own eyes.
 *
 * <p>{@link Camera#capture(org.bukkit.entity.Player, int, java.util.function.Consumer)} shoots from the head of the
 * player it is handed, which is what a viewfinder wants and what nearly every capture is. This is the other case: a
 * security camera on a wall, a periscope, a scrying pool - and the one that needed it, a <b>mirror</b>, whose eye is
 * the viewer's own reflected through the glass.
 *
 * <p>Two things follow from the eye not being a head, and both are the point rather than a side effect:
 *
 * <ul>
 *   <li><b>Everybody is in frame, the viewer included.</b> A capture from inside somebody's skull leaves them out,
 *       because the alternative is a frame filled with the back of their own head. An eye somewhere else has no such
 *       problem, and a mirror that left out the person standing in front of it would be showing an empty room.
 *   <li><b>It can be clipped</b> - see {@link #near}. Without that a reflection is unusable, since the reflected eye
 *       is on the far side of the wall the mirror hangs on and every ray would begin inside a block.
 * </ul>
 *
 * <p>The player is still handed over alongside one of these, because a capture is always <i>for</i> somebody: they
 * decide how far it may see, which walls are showing what, and whose share of the frame budget it spends.
 *
 * @param from   where the eye is and which way it faces. Yaw and pitch are read off it the way they are off a
 *               player's {@code getEyeLocation}, so Bukkit's own convention holds - yaw 0 faces south down +Z and
 *               positive pitch looks down
 * @param near   a plane rays begin at rather than at {@code from}, or null to trace from the eye itself
 * @param window the four edges of the picture, or null to frame it on {@link CameraOptions#fov} the usual way
 * @param mask   which pixels are wanted at all, or null for all of them
 */
public record CameraEye(Location from, @Nullable Plane near, @Nullable Window window, @Nullable Mask mask) {

    /**
     * A half-space a capture is confined to: rays start where they cross it and nothing behind it is drawn.
     *
     * <p>Only ever needed when the eye is somewhere a ray could not have started, which in practice means a
     * reflection. The reflected eye is as far behind the glass as the viewer is in front of it, so the first
     * thing every ray meets is the inside of the wall - and clipping at the glass is what turns that back into
     * the room.
     *
     * <p>Distances are measured from the crossing rather than from the eye, so haze and the distance cap begin at
     * the plane. For a mirror that is the better of the two readings anyway: what fades with distance in a
     * reflection is how far away it looks, and a reflection looks as far away as it is from the glass.
     *
     * @param x       a point the plane passes through
     * @param y       see {@code x}
     * @param z       see {@code x}
     * @param normalX which side is kept, as a unit vector pointing into the half-space that is drawn
     * @param normalY see {@code normalX}
     * @param normalZ see {@code normalX}
     */
    public record Plane(double x, double y, double z, double normalX, double normalY, double normalZ) {

        /** How far in front of the plane a ray actually starts, so a surface lying exactly on it is not self-hit. */
        private static final double SKIN = 1e-4;

        /**
         * How far along {@code (dx, dy, dz)} from the eye a ray enters the half-space, or 0 if it starts inside it
         * and {@link Double#POSITIVE_INFINITY} if it never enters at all.
         *
         * <p>Written for an eye <b>behind</b> the plane, which is the only place a clip is worth asking for. From
         * there a ray either turns toward the kept side and crosses it once, or it runs parallel or further away and
         * has nothing in the half-space to draw.
         *
         * <p>An eye placed <i>in front</i> of its own plane is not clipped at all - it is already inside the
         * half-space, so every ray starts at the eye and one aimed back through the plane goes on out the other
         * side. That is a plane used for something other than what it is for; there is no far bound here.
         */
        public double entry(double eyeX, double eyeY, double eyeZ, double dx, double dy, double dz) {
            double above = (eyeX - x) * normalX + (eyeY - y) * normalY + (eyeZ - z) * normalZ;
            if (above >= 0) return 0;

            double toward = dx * normalX + dy * normalY + dz * normalZ;
            if (toward <= 1e-12) return Double.POSITIVE_INFINITY;

            return -above / toward + SKIN;
        }

        /** Whether a point is on the side that gets drawn, for culling whole entities before any ray is cast. */
        public boolean keeps(double pointX, double pointY, double pointZ) {
            return (pointX - x) * normalX + (pointY - y) * normalY + (pointZ - z) * normalZ >= 0;
        }
    }

    /**
     * How wide the frame is, as the four edges of the picture one block along the way the eye faces.
     *
     * <p>A field of view can only describe a frame centred on where the camera points, and a <b>mirror</b> genuinely
     * needs otherwise: a reflection looks straight out of the glass rather than at the mirror it belongs to, since that
     * is what lets two mirrors on one wall agree about the room. A mirror the viewer is not squarely in front of - one
     * at head height, seen by somebody standing at it - therefore sits off to one side of the frame, and an angle can
     * only reach it by widening until it does. Everything that widening adds is picture the mirror does not show and
     * resolution it does not get; measured on a mirror at a normal mounting height, the glass was getting about a third
     * of the frame.
     *
     * <p>Stating the edges instead also removes both ends of the field-of-view clamp, and a reflection ran into each: a
     * small mirror across the room wants a frame narrower than the ten degree floor, and standing against a tall one
     * wants wider than the hundred and seventy degree ceiling.
     *
     * <p>Tangents rather than angles, because that is the form a ray is built in: a pixel looks along
     * {@code forward + right * sx + up * sy}, for {@code sx} from {@code left} to {@code right} and {@code sy} from
     * {@code bottom} to {@code top}. So a symmetric frame of half-angle {@code a} is {@code -tan a} to {@code tan a} on
     * both, and shifting all four by the same amount pans the picture without changing how much of it there is.
     */
    public record Window(double left, double right, double bottom, double top) {

        public Window {
            if (!(right > left) || !(top > bottom)) {
                throw new IllegalArgumentException("A window needs right past left and top past bottom, which "
                        + left + ".." + right + " by " + bottom + ".." + top + " is not");
            }
        }
    }

    /**
     * Which pixels of a capture are wanted, for a picture that is not going to be shown as a square.
     *
     * <p>A ray is the expensive part of a capture and every one of them is independent, so a pixel nobody will look at
     * is work that can simply not be done. A <b>round</b> mirror is the case this was written for: the glass is a circle
     * inside a square, and the metal frame around it is drawn rather than photographed, so about a third of the frame is
     * never read. Text over a picture, a porthole, a shaped display - all the same shape of saving.
     *
     * <p>Off the main thread, so it does not buy back any of the tick and will not let more live views run - that budget
     * is spent copying the world, which happens per chunk column and is unaffected. What it saves is the tracing, which
     * is real CPU on a busy machine.
     *
     * <p>Unwanted pixels come back <b>transparent</b> rather than as whatever was in the buffer, so a masked capture can
     * be drawn straight over something else.
     *
     * @param width  the width of the capture this mask is for, and {@code height} its height - both of which must match
     *               the {@link CameraOptions} the capture is taken with
     * @param wanted one entry per pixel, row by row, {@code width * height} long. Read and never written, and read from
     *               several threads at once, so do not change it after handing it over
     */
    public record Mask(int width, int height, boolean[] wanted) {

        public Mask {
            if (width <= 0 || height <= 0 || wanted.length != width * height) {
                throw new IllegalArgumentException("A mask for a " + width + "x" + height + " capture is "
                        + width * height + " entries long, which " + wanted.length + " is not");
            }
        }

        /** A mask for a square capture, which is what most of them are. */
        public Mask(int size, boolean[] wanted) {
            this(size, size, wanted);
        }

        public boolean wants(int px, int py) {
            return wanted[py * width + px];
        }
    }

    /** An eye that traces from itself, for a view of somewhere nothing is in the way. */
    public static CameraEye at(Location from) {
        return new CameraEye(from, null, null, null);
    }

    /**
     * The same eye, confined to the front of a plane.
     *
     * @param at     a point the plane passes through - the middle of a mirror, say
     * @param facing which way is drawn. Normalised here, so a {@code BlockFace} direction goes straight in
     */
    public CameraEye clippedTo(Location at, Vector facing) {
        Vector unit = facing.clone().normalize();
        return new CameraEye(from,
                new Plane(at.getX(), at.getY(), at.getZ(), unit.getX(), unit.getY(), unit.getZ()), window, mask);
    }

    /** The same eye, framed on a window of your own rather than on a field of view. See {@link Window}. */
    public CameraEye through(Window frame) {
        return new CameraEye(from, near, frame, mask);
    }

    /** The same eye, drawing only the pixels you are going to look at. See {@link Mask}. */
    public CameraEye onlyWhere(Mask wanted) {
        return new CameraEye(from, near, window, wanted);
    }
}
