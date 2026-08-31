package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.MapSurface;
import de.flog99.mapgui.MapTextFont;
import de.flog99.mapgui.render.Texture;
import de.flog99.mapgui.render.TextureAtlas;
import de.flog99.mapgui.ui.Painter;
import org.bukkit.DyeColor;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * What is written on a sign, drawn into a texture the trace can sample.
 *
 * <p>The one thing in a capture that is not a picture anywhere: a sign's lettering is four strings, and the client
 * draws them with its font every frame. So they are drawn here too, into a strip the size of the client's own text
 * area, and hung in front of the sign as a flat picture.
 *
 * <p>MapGUI's own map font rather than the client's {@code default} font. It is Minecraft's font either way - the map
 * font is the one vanilla rasterises onto maps, with the same glyphs and the same advances - and it is already here,
 * already measured, and already what every other piece of text this plugin draws goes through.
 *
 * <p>Drawn white and coloured afterwards, which keeps the dye exact: the text colour is a property of the sign and
 * not of the glyphs, and multiplying a white glyph by it beats quantising the colour into the map palette first.
 */
final class SignPictures {

    /** The client's own text area, in font pixels: {@code getMaxTextLineWidth} by four lines of {@code lineHeight}. */
    static final int WIDTH = 90;

    static final int LINE_HEIGHT = 10;

    static final int LINES = 4;

    /** How many font pixels there are to a block, which is what {@code RENDER_SCALE} comes to: one over ninety-six. */
    static final float PER_BLOCK = 96;

    /** Kept apart from the asset names, since these pixels are drawn here and no pack could supply them. */
    private static final String NAME = "mapgui/sign/";

    /**
     * What the client draws sign lettering in, per dye - which is a third table alongside a dye's texture colour and
     * its firework colour, and not derivable from either. Vanilla's own numbers.
     */
    private static final Map<DyeColor, Integer> TEXT_COLORS = Map.ofEntries(
            Map.entry(DyeColor.WHITE, 0xFFFFFF),
            Map.entry(DyeColor.ORANGE, 0xFF681F),
            Map.entry(DyeColor.MAGENTA, 0xFF00FF),
            Map.entry(DyeColor.LIGHT_BLUE, 0x9AC0CD),
            Map.entry(DyeColor.YELLOW, 0xFFFF00),
            Map.entry(DyeColor.LIME, 0xBFFF00),
            Map.entry(DyeColor.PINK, 0xFF69B4),
            Map.entry(DyeColor.GRAY, 0x808080),
            Map.entry(DyeColor.LIGHT_GRAY, 0xD3D3D3),
            Map.entry(DyeColor.CYAN, 0x00FFFF),
            Map.entry(DyeColor.PURPLE, 0xA020F0),
            Map.entry(DyeColor.BLUE, 0x0000FF),
            Map.entry(DyeColor.BROWN, 0x8B4513),
            Map.entry(DyeColor.GREEN, 0x00FF00),
            Map.entry(DyeColor.RED, 0xFF0000),
            Map.entry(DyeColor.BLACK, 0x000000)
    );

    /** Black is the colour of an undyed sign, and the one whose number is zero rather than a colour. */
    private static final int DEFAULT_TEXT = 0x000000;

    private SignPictures() {
    }

    /**
     * Draws four lines into the atlas and hands back the name to sample them under, or null when there is nothing
     * written on this side at all.
     *
     * @param key   what makes this drawing different from every other one on the map, since the atlas is keyed by name
     * @param dye   the sign's own text colour, or null for an undyed one
     * @param glowing whether the sign has been glow-inked, which the client draws in the dye's own colour rather than
     *                the darkened one - so here it only means the lettering is not dimmed
     */
    static String publish(String key, List<String> lines, DyeColor dye, boolean glowing, TextureAtlas atlas) {
        if (lines.stream().allMatch(line -> line == null || line.isBlank())) return null;

        MapSurface surface = new MapSurface(WIDTH, LINES * LINE_HEIGHT);
        Painter painter = surface.painter();

        for (int line = 0; line < Math.min(LINES, lines.size()); line++) {
            String text = lines.get(line);
            if (text == null || text.isEmpty()) continue;

            // Centred, the way the client centres each line about the sign's middle.
            int x = (WIDTH - MapTextFont.INSTANCE.widthOf(text)) / 2;
            painter.textLine(x, line * LINE_HEIGHT, text, Color.WHITE, false);
        }

        int color = 0xFF000000 | (dye == null ? DEFAULT_TEXT : TEXT_COLORS.getOrDefault(dye, DEFAULT_TEXT));
        atlas.put(NAME + key, coloured(surface, glowing ? color : dimmed(color)));
        return NAME + key;
    }

    /**
     * The strip as a texture: transparent wherever nothing was drawn, and the text colour wherever something was.
     *
     * <p>Every glyph the map font draws is on or off - there is no blending in it - so a palette index that is not
     * the background is a lit pixel and takes the colour whole.
     */
    private static Texture coloured(MapSurface surface, int color) {
        byte[] drawn = surface.pixels();
        int[] argb = new int[drawn.length];
        for (int i = 0; i < drawn.length; i++) {
            argb[i] = (drawn[i] & 0xFF) < TRANSPARENT ? 0 : color;
        }
        return Texture.opaqueOf(surface.width(), surface.height(), argb);
    }

    /** The palette entries that mean nothing was drawn, which is what an untouched surface is full of. */
    private static final int TRANSPARENT = 4;

    /**
     * Plain sign lettering, which the client draws at four tenths rather than at the dye's full strength. Glow ink
     * is the exception and is what {@code glowing} skips this for.
     */
    private static int dimmed(int color) {
        return color & 0xFF000000
                | (int) ((color >> 16 & 0xFF) * DIM) << 16
                | (int) ((color >> 8 & 0xFF) * DIM) << 8
                | (int) ((color & 0xFF) * DIM);
    }

    private static final float DIM = 0.4f;
}
