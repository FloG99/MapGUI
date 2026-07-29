package de.flog99.mapgui;

import de.flog99.mapgui.ui.Surface;

import java.util.Arrays;

/**
 * A plain byte-per-pixel surface.
 *
 * <p>Tracks a dirty rectangle so a transport can send only what changed instead of pushing all
 * 16 KB every frame.
 */
public final class MapSurface implements Surface {

    private final int width;
    private final int height;
    private final byte[] pixels;

    private int dirtyMinX;
    private int dirtyMinY;
    private int dirtyMaxX;
    private int dirtyMaxY;

    public MapSurface(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new byte[width * height];
        clearDirty();
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void set(int x, int y, byte color) {
        if (!inBounds(x, y)) return;

        int index = y * width + x;
        if (pixels[index] == color) return;

        pixels[index] = color;
        dirtyMinX = Math.min(dirtyMinX, x);
        dirtyMinY = Math.min(dirtyMinY, y);
        dirtyMaxX = Math.max(dirtyMaxX, x);
        dirtyMaxY = Math.max(dirtyMaxY, y);
    }

    @Override
    public byte get(int x, int y) {
        return inBounds(x, y) ? pixels[y * width + x] : 0;
    }

    public byte[] pixels() {
        return pixels;
    }

    /**
     * Copies a rectangle out, one row at a time - which is exactly the layout a map update wants its
     * pixels in.
     */
    public byte[] region(int x, int y, int regionWidth, int regionHeight) {
        byte[] region = new byte[regionWidth * regionHeight];
        for (int row = 0; row < regionHeight; row++) {
            System.arraycopy(pixels, (y + row) * width + x, region, row * regionWidth, regionWidth);
        }
        return region;
    }

    public void fill(byte color) {
        Arrays.fill(pixels, color);
        markAllDirty();
    }

    public boolean isDirty() {
        return dirtyMinX <= dirtyMaxX;
    }

    public int dirtyMinX() {
        return dirtyMinX;
    }

    public int dirtyMinY() {
        return dirtyMinY;
    }

    public int dirtyMaxX() {
        return dirtyMaxX;
    }

    public int dirtyMaxY() {
        return dirtyMaxY;
    }

    public void clearDirty() {
        dirtyMinX = width;
        dirtyMinY = height;
        dirtyMaxX = -1;
        dirtyMaxY = -1;
    }

    public void markAllDirty() {
        dirtyMinX = 0;
        dirtyMinY = 0;
        dirtyMaxX = width - 1;
        dirtyMaxY = height - 1;
    }
}
