package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.map.SavedMapPixels;
import de.flog99.mapgui.render.TextureAtlas;

/**
 * The picture on a map somebody has hung in an item frame.
 *
 * <p>A map's pixels live in the world's own saved data - one byte of palette index per pixel, the same array the
 * ordinary map renderer paints - so they are read from there and handed to {@link MapPicture}, which is where a map
 * of any kind becomes a texture.
 */
final class FramedMaps {

    /** Kept apart from the asset names, since this is a picture no pack could supply. */
    private static final String NAME = "mapgui/framed_map/";

    private final SavedMapPixels saved;

    FramedMaps(SavedMapPixels saved) {
        this.saved = saved;
    }

    /**
     * Publishes one map's pixels into the atlas and hands back the name to draw them under.
     *
     * <p>Null when this server will not give them up, or for a map id nothing has ever drawn. The caller draws the
     * frame and leaves the picture out, which is what a map in a frame looked like before any of this.
     */
    String textureOf(int mapId, TextureAtlas atlas) {
        return MapPicture.publish(NAME + mapId, saved == null ? null : saved.read(mapId), atlas);
    }
}
