package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * A plain byte-per-pixel surface.
 *
 * <p>Tracks what changed per map-sized tile, so a transport can send only what moved instead of pushing all
 * 16 KB of every map every frame.
 *
 * <p>Per tile rather than one rectangle for the whole surface, because one rectangle is the box around
 * everything that changed anywhere: a clock in one corner of a wall and a caption in the other would span the
 * lot, and every map would go out in full for two small changes. A single-map surface has one tile and so
 * behaves exactly as it always did.
 */
public final class MapSurface implements Surface {

    /** One map, which is the grain a map update is sent at and so the grain changes are tracked at. */
    public static final int TILE = 128;

    private final int width;
    private final int height;
    private final byte[] pixels;

    private final int tileCols;
    private final int tileRows;

    /** Bounds of what changed in each tile, in surface coordinates. Inverted when the tile is clean. */
    private final int[] minX;
    private final int[] minY;
    private final int[] maxX;
    private final int[] maxY;

    public MapSurface(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new byte[width * height];

        this.tileCols = Math.ceilDiv(width, TILE);
        this.tileRows = Math.ceilDiv(height, TILE);

        int tiles = tileCols * tileRows;
        this.minX = new int[tiles];
        this.minY = new int[tiles];
        this.maxX = new int[tiles];
        this.maxY = new int[tiles];
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

        int tile = y / TILE * tileCols + x / TILE;
        minX[tile] = Math.min(minX[tile], x);
        minY[tile] = Math.min(minY[tile], y);
        maxX[tile] = Math.max(maxX[tile], x);
        maxY[tile] = Math.max(maxY[tile], y);
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

    /** The same, for a rectangle already worked out - what {@link #dirtyTile} hands back. */
    public byte[] region(Rect rect) {
        return region(rect.x(), rect.y(), rect.width(), rect.height());
    }

    public void fill(byte color) {
        Arrays.fill(pixels, color);
        markAllDirty();
    }

    /** How many maps wide and tall the surface is, which is how many tiles there are to ask about. */
    public int tileCols() {
        return tileCols;
    }

    public int tileRows() {
        return tileRows;
    }

    /**
     * What changed in one tile, in surface coordinates, or null if nothing did.
     *
     * <p>Clamped to the surface, so a tile at the edge of a surface that is not a whole number of maps hands
     * back only the part that exists.
     */
    @Nullable
    public Rect dirtyTile(int col, int row) {
        int tile = row * tileCols + col;
        if (minX[tile] > maxX[tile]) return null;

        return new Rect(minX[tile], minY[tile], maxX[tile] - minX[tile] + 1, maxY[tile] - minY[tile] + 1);
    }

    /**
     * Everything that changed anywhere, as one rectangle, or null if nothing did.
     *
     * <p>For a surface that is one map: on anything bigger this is the box that per-tile tracking exists to
     * avoid sending.
     */
    @Nullable
    public Rect dirtyBounds() {
        int left = width;
        int top = height;
        int right = -1;
        int bottom = -1;

        for (int tile = 0; tile < minX.length; tile++) {
            if (minX[tile] > maxX[tile]) continue;

            left = Math.min(left, minX[tile]);
            top = Math.min(top, minY[tile]);
            right = Math.max(right, maxX[tile]);
            bottom = Math.max(bottom, maxY[tile]);
        }
        return right < left ? null : new Rect(left, top, right - left + 1, bottom - top + 1);
    }

    public boolean isDirty() {
        for (int tile = 0; tile < minX.length; tile++) {
            if (minX[tile] <= maxX[tile]) return true;
        }
        return false;
    }

    public void clearDirty() {
        Arrays.fill(minX, width);
        Arrays.fill(minY, height);
        Arrays.fill(maxX, -1);
        Arrays.fill(maxY, -1);
    }

    public void markAllDirty() {
        for (int row = 0; row < tileRows; row++) {
            for (int col = 0; col < tileCols; col++) {
                int tile = row * tileCols + col;
                minX[tile] = col * TILE;
                minY[tile] = row * TILE;
                maxX[tile] = Math.min(width, (col + 1) * TILE) - 1;
                maxY[tile] = Math.min(height, (row + 1) * TILE) - 1;
            }
        }
    }
}
