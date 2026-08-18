package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.WallTile;
import de.flog99.mapgui.camera.LiveWalls;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * The MapGUI walls in front of the camera, as pictures hanging on the blocks they are mounted to.
 *
 * <p>Everything else in a capture is read out of the world. A wall is not in the world at all - its maps and the
 * frames holding them are sent to each viewer's client and nothing is placed - so a capture that only looked at
 * blocks and entities found bare stone where the video was playing. This asks instead.
 *
 * <p>What the photographer is seeing rather than what the wall is showing in general, which is the same thing on a
 * shared wall and a different picture per person on a per-player one. Somebody who has walked out of range is being
 * sent nothing, and photographs nothing.
 */
final class WallCapture {

    /** As far as an entity is drawn, since a wall map is one and is no more use past that than a mob is. */
    private static final double MAX_DISTANCE = 64;

    /** Kept apart from the asset names, since these pixels are ours and no pack could supply them. */
    private static final String NAME = "mapgui/wall/";

    /** The half circle between a block model's yaw and a painting's, which is what a picture is placed by. */
    private static final float HALF_TURN = 180;

    private static final float QUARTER = 90;

    private WallCapture() {
    }

    static List<EntitySnapshot> take(Player viewer, Location eye, LiveWalls walls, TextureAtlas atlas,
                                     CameraView.ClipPlane clip) {
        if (walls == null) return List.of();

        List<EntitySnapshot> drawn = new ArrayList<>();
        for (WallTile tile : walls.shownTo(viewer)) {
            EntitySnapshot picture = pictureOf(tile, eye, atlas, clip);
            if (picture != null) {
                drawn.add(picture);
            }
        }
        return List.copyOf(drawn);
    }

    /** One map of one wall, or null when it is out of shot or its pixels are not a whole map. */
    private static EntitySnapshot pictureOf(WallTile tile, Location eye, TextureAtlas atlas,
                                            CameraView.ClipPlane clip) {
        BlockFace facing = tile.facing();
        if (facing == null || !facing.isCartesian()) return null;

        double x = tile.blockX() + 0.5;
        double y = tile.blockY() + 0.5;
        double z = tile.blockZ() + 0.5;
        if (eye.distanceSquared(new Location(eye.getWorld(), x, y, z)) > MAX_DISTANCE * MAX_DISTANCE) return null;
        // A mirror is a wall too, and the nearest one to its own camera is itself, sitting on the very plane the
        // frame is clipped at. Dropping it here is what stops a mirror photographing its own glass.
        if (clip != null && !clip.keeps(x, y, z)) return null;

        // The facing belongs in the name as much as the position does: a block can carry a wall on more than one of its
        // faces, and two mirrors either side of a one-block partition is a thing people build deliberately. Keyed on
        // position alone, the second one published overwrote the first and both quads were drawn with whichever picture
        // won - each mirror showing the other's, from a face it is not on.
        String texture = MapPicture.publish(NAME + tile.blockX() + "_" + tile.blockY() + "_" + tile.blockZ()
                + "_" + facing.name(), tile.pixels(), atlas);
        if (texture == null) return null;

        // A floor or a ceiling is the same picture tipped a quarter circle, which lands its top toward north and
        // south respectively - the angle the client draws a horizontal frame at, and the one the layout matches.
        float tipped = (float) Math.toRadians(-QUARTER * facing.getModY());
        // Emissive, because these pixels are a picture and not a surface: whatever the wall is showing was drawn with
        // its own lighting already, and dimming it again by the light where it hangs counts that twice. Which is
        // invisible on one wall and compounds in a mirror facing a mirror - see EntitySnapshot#emissive.
        return EntitySnapshot.wallMap(x, y, z, facingYaw(facing) - HALF_TURN, texture).tipped(tipped).emissive();
    }

    /** The yaw that points a hung thing's front along a block face, as {@code EntityCapture} states one. */
    private static float facingYaw(BlockFace facing) {
        Vector direction = facing.getDirection();
        return (float) -Math.toDegrees(Math.atan2(direction.getX(), direction.getZ()));
    }
}
