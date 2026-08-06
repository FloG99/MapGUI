package de.flog99.mapgui;

import de.flog99.mapgui.ui.Colors;
import de.flog99.mapgui.ui.Painter;
import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.TextAlign;
import de.flog99.mapgui.ui.TextFont;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws an Adventure component with its own colors and styles.
 *
 * <p>Which matters because everything else on a server already speaks components: a MiniMessage string from a
 * config, an item's display name, a chat line worth putting on a wall. Handing one of those to a painter that
 * only takes a {@code String} and one {@code Color} throws away everything the author wrote into it.
 *
 * <p>The tree is walked into runs of same-styled text, and each run is drawn with the style the component
 * inherited. What the font cannot do is worked around rather than dropped: bold on a font with no bold weight
 * is the glyph drawn twice a pixel apart, the way the game itself fakes it. Italic on such a font is left
 * alone, and obfuscated is not animated - a map is redrawn on its own clock, and scrambling it every frame
 * would send the whole line every frame with it.
 */
public final class ComponentText {

    private ComponentText() {
    }

    /** One stretch of text with nothing changing inside it. */
    private record Run(String text, Color color, boolean bold, boolean italic, boolean underlined,
                       boolean struck) {
    }

    /**
     * Draws the component with its top left at {@code x, y}.
     *
     * @param fallback color for text whose component sets none
     * @return how wide it came out, so a caller can put something after it
     */
    public static int draw(Painter painter, int x, int y, Component component, Color fallback, boolean shadow) {
        return draw(painter, x, y, runs(component, fallback), shadow);
    }

    /** Centered or right-aligned inside a box, on one line. */
    public static void draw(Painter painter, Rect box, Component component, Color fallback, boolean shadow, TextAlign align) {
        // Flattened once and used for both: measuring walks the whole tree, and doing it again to draw would
        // rebuild every run of a component that is redrawn each frame.
        List<Run> runs = runs(component, fallback);
        int width = widthOf(painter.font(), runs);

        int x = switch (align) {
            case LEFT -> box.x();
            case CENTER -> box.x() + (box.width() - width) / 2;
            case RIGHT -> box.right() - width;
        };
        draw(painter, x, box.y(), runs, shadow);
    }

    /** How wide {@link #draw} would make it, measured the same way it is drawn. */
    public static int widthOf(TextFont font, Component component) {
        return widthOf(font, runs(component, Color.WHITE));
    }

    private static int draw(Painter painter, int x, int y, List<Run> runs, boolean shadow) {
        int cursor = x;
        for (Run run : runs) {
            cursor = drawRun(painter, cursor, y, run, shadow);
        }
        return cursor - x;
    }

    private static int widthOf(TextFont font, List<Run> runs) {
        int width = 0;
        for (Run run : runs) {
            TextFont styled = font.styled(run.bold(), run.italic());
            boolean fakeBold = fakesBold(font, styled, run);

            String text = styled.sanitize(run.text());
            for (int i = 0; i < text.length(); i++) {
                width += advance(styled, text.charAt(i), fakeBold);
            }
        }
        // The gap after the last glyph is spacing between it and nothing, which is not part of the text.
        return Math.max(0, width - (runs.isEmpty() ? 0 : font.letterSpacing()));
    }

    /**
     * How far the cursor moves past one glyph, which measuring and drawing have to agree on to the pixel.
     *
     * <p>A faked bold is the glyph drawn twice a pixel apart, so it is a pixel wider than the font says.
     */
    private static int advance(TextFont font, char ch, boolean fakeBold) {
        return font.charWidth(ch) + font.letterSpacing() + (fakeBold ? 1 : 0);
    }

    /** A font with no bold weight of its own hands back itself, which is the cue to fake it. */
    private static boolean fakesBold(TextFont base, TextFont styled, Run run) {
        return run.bold() && styled == base;
    }

    /** The text with every style thrown away, for anything that only needs to measure or wrap it. */
    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static int drawRun(Painter painter, int x, int y, Run run, boolean shadow) {
        TextFont base = painter.font();
        TextFont font = base.styled(run.bold(), run.italic());

        // A font with no bold weight of its own gets the vanilla treatment: the same glyph again, one across.
        boolean fakeBold = fakesBold(base, font, run);
        Color shade = Colors.shadow(run.color());

        String text = font.sanitize(run.text());
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (shadow) {
                font.drawChar(painter, cursor + 1, y + 1, ch, shade);
                if (fakeBold) {
                    font.drawChar(painter, cursor + 2, y + 1, ch, shade);
                }
            }

            font.drawChar(painter, cursor, y, ch, run.color());
            if (fakeBold) {
                font.drawChar(painter, cursor + 1, y, ch, run.color());
            }
            cursor += advance(font, ch, fakeBold);
        }

        if (run.underlined()) {
            painter.line(x, y + font.lineHeight(), cursor - 1, y + font.lineHeight(), run.color());
        }
        if (run.struck()) {
            painter.line(x, y + font.lineHeight() / 2, cursor - 1, y + font.lineHeight() / 2, run.color());
        }
        return cursor;
    }

    private static List<Run> runs(Component component, Color fallback) {
        List<Run> runs = new ArrayList<>();
        flatten(component, Style.empty(), fallback, runs);
        return runs;
    }

    /**
     * Depth first, carrying the style down.
     *
     * <p>A child's own style wins and the parent's fills in whatever the child left unsaid, which is how
     * components inherit anywhere else.
     */
    private static void flatten(Component component, Style inherited, Color fallback, List<Run> out) {
        Style style = component.style().merge(inherited, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);

        String text = textOf(component);
        if (!text.isEmpty()) {
            out.add(new Run(
                    text,
                    colorOf(style, fallback),
                    style.hasDecoration(TextDecoration.BOLD),
                    style.hasDecoration(TextDecoration.ITALIC),
                    style.hasDecoration(TextDecoration.UNDERLINED),
                    style.hasDecoration(TextDecoration.STRIKETHROUGH)
            ));
        }

        for (Component child : component.children()) {
            flatten(child, style, fallback, out);
        }
    }

    /**
     * This node's own text, without its children's.
     *
     * <p>Plain text is the common case and comes straight off the component. Anything else - a translation, a
     * keybind, a scoreboard value - is handed to the plain serializer with its children taken off, since those
     * are walked separately and would otherwise be drawn twice.
     */
    private static String textOf(Component component) {
        if (component instanceof TextComponent text) return text.content();

        return PlainTextComponentSerializer.plainText().serialize(component.children(List.of()));
    }

    private static Color colorOf(Style style, Color fallback) {
        TextColor color = style.color();
        return color == null ? fallback : new Color(color.value());
    }
}
