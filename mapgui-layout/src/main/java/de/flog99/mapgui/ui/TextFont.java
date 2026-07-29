package de.flog99.mapgui.ui;

import java.util.ArrayList;
import java.util.List;

/** Bitmap font measurements and glyph blitting, so the layout engine can size text. */
public interface TextFont {

    int lineHeight();

    /** Pixel width of {@code text} as it would be drawn. Must tolerate any input. */
    int widthOf(String text);

    /** Replaces characters the font cannot draw. */
    String sanitize(String text);

    void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip);

    int charWidth(char ch);

    /**
     * Splits text into lines that fit, hard-breaking any single word that still doesn't.
     *
     * <p>Candidates are measured with {@link #widthOf} rather than by adding up word widths.
     * Accumulating is off by two pixels per space, because each measured word drops the trailing
     * advance gap, and being off here means text sits a couple of pixels short of where the layout
     * thinks it ends.
     *
     * <p>Lives on the font so measuring and painting can never disagree about how many lines a
     * label takes.
     */
    default List<String> wrap(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        StringBuilder line = new StringBuilder();
        for (String word : sanitize(text).split(" ", -1)) {
            if (line.isEmpty()) {
                line.append(word);
            } else if (widthOf(line + " " + word) <= maxWidth) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }

            while (widthOf(line.toString()) > maxWidth) {
                int fits = longestPrefix(line.toString(), maxWidth);
                if (fits <= 0) break;

                lines.add(line.substring(0, fits));
                line.delete(0, fits);
            }
        }

        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Number of leading characters that fit in {@code maxWidth}. */
    default int longestPrefix(String text, int maxWidth) {
        int fits = 0;
        while (fits < text.length() && widthOf(text.substring(0, fits + 1)) <= maxWidth) {
            fits++;
        }
        return fits;
    }
}
