package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A layer that stops being readable while it is open.
 *
 * <p>Which is not a hypothetical: a plugin installing its own pack into {@code assets/} writes over a file MapGUI
 * already holds, and every reader here treats the resulting failure the same way it treats a file that was never
 * there - falls through to the layer underneath and draws something. The camera goes on working and quietly
 * photographs a plugin's custom items as their base material, with nothing said anywhere.
 *
 * <p>So the one thing that must hold is that the stack remembers. Truncating the file is the deterministic version
 * of the overwrite: the table of contents was read when the zip was opened and still points at offsets that are
 * now past the end.
 */
class DamagedPackTest {

    @TempDir
    Path dir;

    @Test
    void aLayerThatStopsBeingReadableIsRemembered() throws IOException {
        Path file = Zips.write(dir.resolve("yourpack.zip"),
                Map.of("assets/yourpack/textures/item/thing.png", "not really a png, but bytes are bytes"));

        try (AssetStack stack = AssetStack.of(
                List.of(AssetPack.open(file)),
                AssetPack.open(Zips.write(dir.resolve("base.zip"), Zips.completeBase("26.2"))),
                "26.2")) {

            assertTrue(stack.damage().isEmpty(), "nothing is wrong with it yet");

            Files.write(file, new byte[0]);

            assertThrows(IOException.class, () -> stack.read("assets/yourpack/textures/item/thing.png"),
                    "the entry is still in the table of contents, and the bytes it points at are gone");

            assertEquals(1, stack.damage().size(), "which is the only trace there will ever be of it");
            assertTrue(stack.damage().getFirst().startsWith("yourpack.zip"),
                    "named, because the point is to say which file to go and fix: " + stack.damage());
        }
    }
}
