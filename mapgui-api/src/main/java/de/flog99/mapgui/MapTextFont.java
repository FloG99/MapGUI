package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import de.flog99.mapgui.ui.TextFont;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

/**
 * The vanilla map font.
 *
 * <p>Bukkit's own {@code getWidth} throws on any character the font can't draw, which is no use
 * for player-supplied text, so measuring and sanitising are done here instead.
 */
public final class MapTextFont implements TextFont {

    public static final MapTextFont INSTANCE = new MapTextFont();

    private static final char FALLBACK = '?';

    private final MapFont font = MinecraftFont.Font;

    private MapTextFont() {
    }

    @Override
    public int lineHeight() {
        return font.getHeight();
    }

    @Override
    public int widthOf(String text) {
        if (text == null || text.isEmpty()) return 0;

        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += charWidth(text.charAt(i)) + 1;
        }
        return Math.max(0, width - 1);
    }

    @Override
    public int charWidth(char ch) {
        MapFont.CharacterSprite sprite = spriteOf(ch);
        return sprite == null ? 0 : sprite.getWidth();
    }

    @Override
    public String sanitize(String text) {
        if (text == null) return "";

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            out.append(spriteOf(ch) == null ? FALLBACK : ch);
        }
        return out.toString();
    }

    @Override
    public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
        MapFont.CharacterSprite sprite = spriteOf(ch);
        if (sprite == null) return;

        for (int row = 0; row < font.getHeight(); row++) {
            for (int column = 0; column < sprite.getWidth(); column++) {
                if (!sprite.get(row, column)) continue;

                int px = x + column;
                int py = y + row;
                if (clip.contains(px, py)) {
                    surface.set(px, py, color);
                }
            }
        }
    }

    private MapFont.CharacterSprite spriteOf(char ch) {
        try {
            return font.getChar(ch);
        } catch (Exception e) {
            return null;
        }
    }
}
