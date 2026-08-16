package de.flog99.mapgui;

import de.flog99.mapgui.ui.Rect;
import de.flog99.mapgui.ui.Surface;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

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
 *
 * <p>Within a tile it is per row, which is what {@link #dirtyRegions} needs to split one map into several
 * rectangles. Rows cost no more to keep than the box they replace - a write touches its tile's row range and
 * its own row's span, which is the same four comparisons the box took - and they carry enough to work out
 * afterwards whether sending several rectangles beats sending the one box around them.
 */
public final class MapSurface implements Surface {

    /** One map, which is the grain a map update is sent at and so the grain changes are tracked at. */
    public static final int TILE = 128;

    private final int width;
    private final int height;
    private final byte[] pixels;

    private final int tileCols;
    private final int tileRows;

    /** First and last row of each tile that changed, in surface coordinates. Inverted when the tile is clean. */
    private final int[] minRow;
    private final int[] maxRow;

    /**
     * What changed in one row of one tile: an x range in surface coordinates, right exclusive, inverted when
     * that row of that tile is clean. Indexed by {@code row * tileCols + column}, so the tiles of a wall sit
     * beside each other and a tile is every stride'th entry.
     */
    private final int[] spanLeft;
    private final int[] spanRight;

    public MapSurface(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new byte[width * height];

        this.tileCols = Math.ceilDiv(width, TILE);
        this.tileRows = Math.ceilDiv(height, TILE);

        int tiles = tileCols * tileRows;
        this.minRow = new int[tiles];
        this.maxRow = new int[tiles];
        this.spanLeft = new int[height * tileCols];
        this.spanRight = new int[height * tileCols];
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
        markChanged(y, x / TILE, x, x + 1);
    }

    /**
     * Records that one row of one tile changed between {@code from} and {@code to}, right exclusive.
     *
     * <p>Of one tile, since that is the grain the spans are kept at and a map update never spans two of them.
     */
    private void markChanged(int y, int col, int from, int to) {
        int tile = y / TILE * tileCols + col;
        minRow[tile] = Math.min(minRow[tile], y);
        maxRow[tile] = Math.max(maxRow[tile], y);

        int span = y * tileCols + col;
        spanLeft[span] = Math.min(spanLeft[span], from);
        spanRight[span] = Math.max(spanRight[span], to);
    }

    /**
     * Takes another surface's pixels, marking only the ones that really changed.
     *
     * <p>What lets anything be drawn on a canvas and sent from here. A surface that is wiped and painted over
     * every frame has changed everywhere by the time it is finished - the wipe saw to that - so a frame that
     * ends up identical still goes out in full. Painting on a canvas and crossing over through here means only
     * real differences are ever sent, and a settled screen sends nothing at all.
     *
     * <p>A row of a tile at a time rather than a pixel at a time: the outermost differences are found with a
     * comparison the machine does sixteen bytes at once, the run between them is copied in one go, and the
     * span is recorded once instead of once per pixel. What ends up marked is exactly what setting every pixel
     * by hand would have marked, since a row of a tile only ever keeps the span around what changed - which is
     * also why this stops at each tile rather than scanning the whole row: a wall would otherwise sweep two
     * far-apart changes into one span per tile between them.
     */
    public void copyFrom(MapSurface other) {
        if (other.width != width || other.height != height) {
            throw new IllegalArgumentException("a surface can only be copied from one of the same size");
        }

        byte[] source = other.pixels;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int col = 0; col < tileCols; col++) {
                int start = row + col * TILE;
                int end = row + Math.min(width, (col + 1) * TILE);

                int from = Arrays.mismatch(source, start, end, pixels, start, end);
                if (from < 0) continue;

                // There is at least one difference, so this stops at it rather than running off the tile.
                int to = end - start;
                while (source[start + to - 1] == pixels[start + to - 1]) to--;

                System.arraycopy(source, start + from, pixels, start + from, to - from);
                markChanged(y, col, col * TILE + from, col * TILE + to);
            }
        }
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
        if (minRow[tile] > maxRow[tile]) return null;

        int left = width;
        int right = -1;
        for (int y = minRow[tile]; y <= maxRow[tile]; y++) {
            int span = y * tileCols + col;
            if (spanLeft[span] >= spanRight[span]) continue;

            left = Math.min(left, spanLeft[span]);
            right = Math.max(right, spanRight[span]);
        }
        // The tracked rows are exact, so the first and last are dirty and the height needs no searching.
        return new Rect(left, minRow[tile], right - left, maxRow[tile] - minRow[tile] + 1);
    }

    /**
     * The same change, as the cheapest set of rectangles to send it as rather than as the one box around it.
     *
     * <p>What a map update actually wants. A box is one packet whatever is in it, so a tile whose top strip
     * and bottom strip both changed sends the whole 16 KB between them; two rectangles send the two strips.
     * Splitting only happens where the arithmetic says it pays, so a full redraw is still one rectangle and
     * still one packet.
     *
     * @return empty when the tile changed nothing
     */
    public List<Rect> dirtyRegions(int col, int row) {
        int tile = row * tileCols + col;
        if (minRow[tile] > maxRow[tile]) return List.of();

        return Patches.plan(spanLeft, spanRight, tileCols, col, minRow[tile], maxRow[tile]);
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

        for (int row = 0; row < tileRows; row++) {
            for (int col = 0; col < tileCols; col++) {
                Rect tile = dirtyTile(col, row);
                if (tile == null) continue;

                left = Math.min(left, tile.x());
                top = Math.min(top, tile.y());
                right = Math.max(right, tile.right());
                bottom = Math.max(bottom, tile.bottom());
            }
        }
        return right <= left ? null : new Rect(left, top, right - left, bottom - top);
    }

    /**
     * Everything that changed anywhere, as the cheapest set of rectangles rather than as one box.
     *
     * <p>The counterpart to {@link #dirtyBounds} and for the same surface: one that is one map. On anything
     * bigger these cross tile boundaries, and a map update cannot.
     */
    public List<Rect> dirtyRegions() {
        int first = height;
        int last = -1;
        for (int tile = 0; tile < minRow.length; tile++) {
            if (minRow[tile] > maxRow[tile]) continue;

            first = Math.min(first, minRow[tile]);
            last = Math.max(last, maxRow[tile]);
        }
        if (last < first) return List.of();

        // One column of spans is already the whole width of a surface that is one map wide, which is the
        // only shape this is for; anything wider has to have its tiles put back together first.
        if (tileCols == 1) return Patches.plan(spanLeft, spanRight, 1, 0, first, last);

        int[] left = new int[height];
        int[] right = new int[height];
        Arrays.fill(left, width);
        Arrays.fill(right, -1);

        for (int y = first; y <= last; y++) {
            for (int col = 0; col < tileCols; col++) {
                int span = y * tileCols + col;
                if (spanLeft[span] >= spanRight[span]) continue;

                left[y] = Math.min(left[y], spanLeft[span]);
                right[y] = Math.max(right[y], spanRight[span]);
            }
        }
        return Patches.plan(left, right, 1, 0, first, last);
    }

    public boolean isDirty() {
        for (int tile = 0; tile < minRow.length; tile++) {
            if (minRow[tile] <= maxRow[tile]) return true;
        }
        return false;
    }

    public void clearDirty() {
        Arrays.fill(minRow, height);
        Arrays.fill(maxRow, -1);
        Arrays.fill(spanLeft, width);
        Arrays.fill(spanRight, -1);
    }

    public void markAllDirty() {
        for (int row = 0; row < tileRows; row++) {
            for (int col = 0; col < tileCols; col++) {
                int tile = row * tileCols + col;
                minRow[tile] = row * TILE;
                maxRow[tile] = Math.min(height, (row + 1) * TILE) - 1;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int col = 0; col < tileCols; col++) {
                int span = y * tileCols + col;
                spanLeft[span] = col * TILE;
                spanRight[span] = Math.min(width, (col + 1) * TILE);
            }
        }
    }
}
