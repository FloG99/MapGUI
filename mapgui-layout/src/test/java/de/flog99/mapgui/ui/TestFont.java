package de.flog99.mapgui.ui;

/** Fixed-width stand-in so layout tests don't need a server for font metrics. */
final class TestFont implements TextFont {

    static final int CHAR_WIDTH = 5;
    static final TestFont INSTANCE = new TestFont();

    @Override
    public int lineHeight() {
        return 8;
    }

    @Override
    public int widthOf(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() * (CHAR_WIDTH + 1) - 1;
    }

    @Override
    public int charWidth(char ch) {
        return CHAR_WIDTH;
    }

    @Override
    public String sanitize(String text) {
        return text == null ? "" : text;
    }

    @Override
    public void drawChar(Surface surface, int x, int y, char ch, byte color, Rect clip) {
        for (int row = 0; row < lineHeight(); row++) {
            for (int column = 0; column < CHAR_WIDTH; column++) {
                if (clip.contains(x + column, y + row)) {
                    surface.set(x + column, y + row, color);
                }
            }
        }
    }
}
