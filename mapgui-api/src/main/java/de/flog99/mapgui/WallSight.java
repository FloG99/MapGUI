package de.flog99.mapgui;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a wall could be on a viewer's screen at all, so that one nobody is looking at costs nothing.
 *
 * <p>A wall is a rectangle and a view is a pyramid, so the test is a rejection rather than a sample: if all
 * four corners are off the same side of the view then the wall is off screen, because a rectangle lies wholly
 * on one side of any plane its corners all do. Only that direction holds - a wall clipped by the corner of the
 * pyramid can be off screen without any single side rejecting it - and that is the harmless way round, since
 * being kept only costs pixels.
 *
 * <p>Testing whether any corner is <i>inside</i> instead looks equivalent and is not: stand a block from a
 * six-by-six wall and every corner is off screen while the picture fills your monitor. Rays at the corners have
 * the same hole, and cost a voxel walk each to find it.
 *
 * <p>Twelve multiplies and no trigonometry, so it runs per viewer per tick without being thought about. What
 * costs is looking for something in the way, which is why nothing traces until the arithmetic has had its say.
 */
final class WallSight {

    /**
     * Half the view this assumes, vertically, with room to spare on every client there is.
     *
     * <p>The client ships at seventy degrees and its slider goes to a hundred and ten, which is fifty-five
     * either side - but Minecraft also scales the field of view with movement, so sprinting or a speed effect
     * pushes a maxed-out slider past sixty. The server is told none of it.
     *
     * <p>The two errors are not equal. Too wide only sends pixels that were not needed; too narrow freezes a
     * video wall for somebody who can see it, and only while they are sprinting, which is a fine bug to be
     * handed. So this clears the worst case rather than fitting the common one.
     */
    private static final double TAN_UP = Math.tan(Math.toRadians(65));

    /**
     * The same across, where the screen's aspect ratio widens it again and is also unknown.
     *
     * <p>Sized off the vertical worst case rather than off this one: sixty degrees up, times 32:9, is a bit over
     * eighty-two either side. So an ultrawide with the slider maxed is inside it, which the more obvious eighty
     * would not have been.
     */
    private static final double TAN_ACROSS = Math.tan(Math.toRadians(83));

    /**
     * How long after last having the wall on screen a viewer goes on being sent frames.
     *
     * <p>Heads turn several times a second and resuming costs a whole frame, so without this a viewer glancing
     * about in front of a video wall would cost more than one who was simply sent everything. It covers
     * stepping across the wall's plane for the same reason.
     */
    private static final long GRACE_MS = 500;

    /**
     * How far across and up the picture occlusion is sampled, as fractions - the centres of a three-by-three.
     *
     * <p>Interior points rather than the corners, which are the worst places to ask: a wall seen through a
     * doorway has all four of them behind the frame while its middle is in plain view. Nine is a compromise
     * either way, so a wall visible only through a slit narrower than a third of it can still be missed.
     *
     * <p>The middle comes first because that is where a wall is most likely to be visible from, and one
     * unblocked sample is the whole answer - so the ordinary case of a wall in plain sight costs a single ray.
     */
    private static final double[] SAMPLE = {1 / 2.0, 1 / 6.0, 5 / 6.0};

    /**
     * How long a traced verdict is trusted for at the outside, having stood still all the while.
     *
     * <p>Occlusion is a question about where somebody is, so moving is what really invalidates it - but not the
     * only thing: somebody can build a wall in front of a screen, and this is what notices.
     */
    private static final long STALEST_MS = 500;

    /**
     * And how long it is trusted for regardless, even from somebody sprinting.
     *
     * <p>Walking covers a fifth of a block a tick, so without a floor here every moving viewer re-traces every
     * tick against every wall in range - and a plaza of screens with a crowd walking through it is a great many
     * rays for an answer that has barely changed. The cost of the floor is that stepping out from behind a pillar
     * can take this long to resume, which the grace period already covers several times over.
     */
    private static final long SOONEST_MS = 150;

    /** How far a viewer can drift and still be treated as not having moved. Absorbs the jitter of standing still. */
    private static final double STILL_SQUARED = 0.1 * 0.1;

    private final WallLayout layout;

    /** The corners of the picture, in world coordinates. A wall does not move, so they are worked out once. */
    private final double[][] corners;

    /** The nine points occlusion is traced to, likewise fixed for the life of the wall. */
    private final double[][] samples;

    /**
     * When each viewer last had the wall on screen.
     *
     * <p>Absent means never, which is deliberately not the same as "a while ago": somebody who walks up to a
     * wall backwards is sent nothing at all until they turn round, rather than being handed a frame on arrival
     * for the grace period to cover. On a six-by-six that would be half a megabyte per passer-by.
     */
    private final Map<UUID, Long> lastOnScreen = new HashMap<>();

    /** The last traced verdict per viewer, and where they were standing when it was taken. */
    private final Map<UUID, Trace> traced = new HashMap<>();

    WallSight(WallLayout layout) {
        this.layout = layout;
        this.corners = layout.corners();
        this.samples = new double[SAMPLE.length * SAMPLE.length][];

        int at = 0;
        for (double across : SAMPLE) {
            for (double above : SAMPLE) {
                samples[at++] = layout.pointAt(across, above);
            }
        }
    }

    /** What tracing said, and the eye it was said about - so standing still does not pay for it twice. */
    private record Trace(double x, double y, double z, long at, boolean hidden) {

        boolean stillHolds(Location eye, long now) {
            long since = now - at;
            if (since < SOONEST_MS) return true;
            if (since >= STALEST_MS) return false;

            double dx = eye.getX() - x;
            double dy = eye.getY() - y;
            double dz = eye.getZ() - z;
            return dx * dx + dy * dy + dz * dz < STILL_SQUARED;
        }
    }

    /**
     * Whether this viewer should still be sent frames, having been looking away for less than the grace
     * period if they are looking away at all.
     *
     * <p>Called once a tick per viewer, and remembers between calls - so it is the wall's to own and not
     * something to ask twice.
     */
    boolean streaming(Player player, long now, boolean pointedAt) {
        UUID id = player.getUniqueId();
        Location eye = player.getEyeLocation();

        // Pointing at it settles it outright, and for nothing: deciding that already traced a clear line to the
        // very pixel under their cursor, which is finer than nine samples of the whole wall can be. Without it a
        // menu glimpsed through a gap narrower than the sampling could freeze under the cursor using it.
        //
        // Ordered so that somebody facing away never pays for a ray either: the arithmetic comes before tracing.
        if (pointedAt || (onScreen(eye) && !hidden(eye, id, now))) {
            lastOnScreen.put(id, now);
            return true;
        }

        Long last = lastOnScreen.get(id);
        return last != null && now - last < GRACE_MS;
    }

    void forget(UUID player) {
        lastOnScreen.remove(player);
        traced.remove(player);
    }

    void clear() {
        lastOnScreen.clear();
        traced.clear();
    }

    /**
     * Whether there is something solid in front of every part of the wall - a wall behind a wall, a screen
     * across a hill, a cinema seen from the corridor outside it.
     *
     * <p>Traced rather than reasoned about, and so the expensive half: nine rays where the rest of this is a
     * dozen multiplies. What keeps it affordable is that the answer only changes when somebody moves or
     * somebody builds, so it is remembered per viewer and taken again when one of those happens.
     *
     * <p>The two outcomes cost very differently, in the right direction. A wall in plain sight is answered by
     * its first sample, which is one ray. A hidden one takes all nine, but every one of them stops at whatever
     * is in the way rather than running the length of the room - and it is buying back megabytes a second.
     */
    private boolean hidden(Location eye, UUID id, long now) {
        Trace last = traced.get(id);
        if (last != null && last.stillHolds(eye, now)) return last.hidden();

        boolean hidden = true;
        for (double[] sample : samples) {
            if (!Sightlines.opaque(eye, sample[0], sample[1], sample[2])) {
                hidden = false;
                break;
            }
        }

        traced.put(id, new Trace(eye.getX(), eye.getY(), eye.getZ(), now, hidden));
        return hidden;
    }

    /**
     * Whether any part of the wall could fall inside the view from this eye.
     *
     * <p>Conservative about the view it assumes, and within that assumption exact: it may keep a wall that is
     * in fact hidden - one clipped by the corner of the view pyramid, say - but it never drops one that falls
     * inside. Testing whether any <i>corner</i> is inside would not manage that, since a wall standing close
     * enough has all four outside a view it fills.
     *
     * <p>A function of the eye and nothing else, which is what makes it testable without a server.
     */
    boolean onScreen(Location eye) {
        // The back of a wall is not a picture: a frame draws nothing from behind and the block it hangs on is
        // in the way regardless. Cheapest test there is, and it settles half of everywhere.
        if (layout.depthOf(eye.getX(), eye.getY(), eye.getZ()) <= 0) return false;

        Vector look = eye.getDirection();

        double fx = look.getX();
        double fy = look.getY();
        double fz = look.getZ();

        // Screen-right is forward crossed with world up, which is horizontal by construction and so has no y.
        double horizontal = Math.sqrt(fx * fx + fz * fz);
        double rx;
        double rz;
        if (horizontal < 1e-9) {
            // Straight up or down, where that cross product vanishes. Taken from the yaw instead, which is the
            // angle the client rolls the view to - a right picked arbitrarily would test a view on its side.
            double yaw = Math.toRadians(eye.getYaw());
            rx = -Math.cos(yaw);
            rz = -Math.sin(yaw);
        } else {
            rx = -fz / horizontal;
            rz = fx / horizontal;
        }

        // Screen-up is right crossed with forward, both already unit and perpendicular.
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;

        boolean behind = true;
        boolean beyondRight = true;
        boolean beyondLeft = true;
        boolean above = true;
        boolean below = true;

        for (double[] corner : corners) {
            double px = corner[0] - eye.getX();
            double py = corner[1] - eye.getY();
            double pz = corner[2] - eye.getZ();

            double ahead = px * fx + py * fy + pz * fz;
            double across = px * rx + pz * rz;
            double up = px * ux + py * uy + pz * uz;

            // Half-spaces through the eye, so each comparison holds whichever side of it the corner is on.
            double wide = ahead * TAN_ACROSS;
            double tall = ahead * TAN_UP;

            behind &= ahead <= 0;
            beyondRight &= across > wide;
            beyondLeft &= across < -wide;
            above &= up > tall;
            below &= up < -tall;
        }
        return !(behind || beyondRight || beyondLeft || above || below);
    }
}
