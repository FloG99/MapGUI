package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.MapColors;
import de.flog99.mapgui.render.Texture;
import de.flog99.mapgui.render.TextureAtlas;

import java.awt.Color;

/**
 * One map's worth of palette indices as a texture the trace can sample.
 *
 * <p>The two pictures in a capture that are nowhere in the assets go through here: a map hung in a real item frame,
 * whose bytes are in the world's saved data, and a map of a MapGUI wall, whose bytes exist only in a viewer's client.
 * Both are the same 16 KB of palette index that an ordinary map update carries.
 *
 * <p>Published per capture rather than cached, because neither is a fixed picture - one fills in as somebody walks
 * around with it and the other is a video. Widening sixteen kilobytes is cheap next to tracing what it hangs on.
 */
final class MapPicture {

    /** One map's edge. The array is indexed x + y * 128, with row zero along the top as the viewer sees it. */
    static final int SIZE = 128;

    /**
     * How many palette entries mean "nothing here".
     *
     * <p>The first base colour is vanilla's {@code NONE}, and its four shades are what an unexplored map is full of.
     * They are transparent rather than black, which is what lets a frame show through the middle of a fresh map.
     */
    private static final int NOTHING = 4;

    private MapPicture() {
    }

    /**
     * Publishes one map's pixels into the atlas under {@code name} and hands that name back.
     *
     * @return null for anything that is not a whole map, which is the caller's cue to draw no picture at all
     */
    static String publish(String name, byte[] pixels, TextureAtlas atlas) {
        if (pixels == null || pixels.length != SIZE * SIZE) return null;

        int[] argb = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            argb[i] = color(pixels[i]);
        }

        atlas.put(name, Texture.opaqueOf(SIZE, SIZE, argb));
        return name;
    }

    private static int color(byte index) {
        if ((index & 0xFF) < NOTHING) return 0;

        Color found = MapColors.INSTANCE.color(index);
        return found == null ? 0 : 0xFF000000 | found.getRGB() & 0xFFFFFF;
    }
}
