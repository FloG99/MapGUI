package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.map.SavedMapPixels;
import de.flog99.mapgui.render.Texture;
import de.flog99.mapgui.render.TextureAtlas;

import java.awt.Color;

/**
 * The picture on a map somebody has hung on a wall, as a texture the trace can sample.
 *
 * <p>The one thing in a capture whose picture is nowhere in the assets. A map's pixels live in the world's own saved
 * data - one byte of palette index per pixel, the same array the ordinary map renderer paints - so they are read from
 * there and turned into a texture per capture.
 *
 * <p>Per capture rather than cached, because a map is not a fixed picture: it fills in as somebody walks around with
 * it, and a cached one would show a frame on a wall filling in a week late. Reading sixteen kilobytes and widening it
 * is cheap next to tracing the frame it hangs in.
 */
final class FramedMaps {

    /** One map's edge. The array is indexed x + y * 128, with row zero along the north edge. */
    private static final int SIZE = 128;

    /**
     * How many palette entries mean "nothing here".
     *
     * <p>The first base colour is vanilla's {@code NONE}, and its four shades are what an unexplored map is full of.
     * They are transparent rather than black, which is what lets the frame show through the middle of a fresh map.
     */
    private static final int NOTHING = 4;

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
        byte[] pixels = saved == null ? null : saved.read(mapId);
        if (pixels == null || pixels.length != SIZE * SIZE) return null;

        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            argb[i] = color(pixels[i]);
        }

        String name = NAME + mapId;
        atlas.put(name, Texture.opaqueOf(SIZE, SIZE, argb));
        return name;
    }

    private static int color(byte index) {
        if ((index & 0xFF) < NOTHING) return 0;

        Color found = MapColors.INSTANCE.color(index);
        return found == null ? 0 : 0xFF000000 | found.getRGB() & 0xFFFFFF;
    }
}
