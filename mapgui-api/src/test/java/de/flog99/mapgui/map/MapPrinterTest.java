package de.flog99.mapgui.map;

import de.flog99.mapgui.camera.Camera;
import de.flog99.mapgui.camera.CameraShot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cutting a capture into map-sized tiles.
 *
 * <p>Worth a test because the mistake it can make is invisible in the code and obvious on a wall: the capture and the
 * tile have different row strides, 256 against 128, and getting that wrong shears the picture diagonally rather than
 * failing. Nothing else here is testable off a server, since the rest is the server making real maps.
 */
class MapPrinterTest {

    private static final int TILE = Camera.MAP_SIZE;

    /** A value that depends on both coordinates, so a tile that reads the wrong row or column cannot match by luck. */
    private static byte at(int x, int y) {
        return (byte) (x * 7 + y * 13);
    }

    private static CameraShot capture(int size) {
        byte[] pixels = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = at(x, y);
            }
        }
        return new CameraShot(size, size, pixels, "26.2");
    }

    @Test
    void everyTilePixelComesFromItsOwnCornerOfTheCapture() {
        int across = 2;
        List<byte[]> tiles = MapPrinter.cut(capture(MapPrinter.sizeFor(across)));
        assertEquals(across * across, tiles.size());

        for (int piece = 0; piece < tiles.size(); piece++) {
            // Reading order, so the piece index is the row and column it came from.
            int row = piece / across;
            int column = piece % across;
            byte[] tile = tiles.get(piece);
            assertEquals(TILE * TILE, tile.length);

            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    assertEquals(at(column * TILE + x, row * TILE + y), tile[y * TILE + x],
                            "tile row " + row + " column " + column + " at " + x + "," + y);
                }
            }
        }
    }

    /** The tiles together have to be the whole capture and nothing twice, whatever the grid. */
    @Test
    void theTilesCoverTheCaptureExactlyOnce() {
        int across = 3;
        int size = MapPrinter.sizeFor(across);
        int[] seen = new int[size * size];

        List<byte[]> tiles = MapPrinter.cut(capture(size));
        for (int piece = 0; piece < tiles.size(); piece++) {
            int row = piece / across;
            int column = piece % across;
            for (int y = 0; y < TILE; y++) {
                for (int x = 0; x < TILE; x++) {
                    seen[(row * TILE + y) * size + column * TILE + x]++;
                }
            }
        }

        for (int pixel : seen) {
            assertEquals(1, pixel, "every pixel of the capture belongs to exactly one tile");
        }
    }

    @Test
    void aCaptureThatIsNotWholeMapsIsRefusedRatherThanCropped() {
        CameraShot ragged = capture(TILE + 10);
        assertEquals(0, MapPrinter.mapsAcross(ragged));
        assertThrows(IllegalArgumentException.class, () -> MapPrinter.cut(ragged));
    }

    @Test
    void aCaptureThatIsNotSquareIsRefusedToo() {
        CameraShot oblong = new CameraShot(TILE * 2, TILE, new byte[TILE * 2 * TILE], "26.2");
        assertEquals(0, MapPrinter.mapsAcross(oblong));
        assertThrows(IllegalArgumentException.class, () -> MapPrinter.cut(oblong));
    }
}
