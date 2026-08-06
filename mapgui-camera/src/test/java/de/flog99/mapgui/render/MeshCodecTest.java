package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The json the extracted geometry rides in, which is written once per Minecraft version and read once per load.
 *
 * <p>What matters is that nothing is lost on the way through - a pose, a scale, an absent face or a nested part
 * silently dropped would draw a mob subtly wrong rather than fail - and that a file this does not recognize comes
 * back empty rather than throwing, since empty means bounding boxes and throwing means no capture at all.
 */
class MeshCodecTest {

    private static Map<String, List<MeshPart>> mesh() {
        MeshCube plain = MeshCube.box(-4, 0, -4, 8, 8, 8, 0, 0, 64, 32, 0);

        // One face left undrawn and one with corners of its own, since those are the two things a naive
        // round trip flattens.
        float[][] faces = new float[6][];
        faces[Direction.NORTH.ordinal()] = new float[]{0.125f, 0.25f, 0.5f, 0.25f, 0.125f, 0.75f, 0.5f, 0.75f};
        faces[Direction.UP.ordinal()] = new float[]{0, 0, 1, 0, 0, 1, 1, 1};
        MeshCube partial = new MeshCube(-1, -2, -3, 4, 5, 6, faces);

        return Map.of("test", List.of(new MeshPart("root", false, 1, 24.016f, -3,
                0.25f, -0.5f, 0.75f, 1.5f, 1.5f, 2f,
                List.of(plain),
                List.of(MeshPart.at("head", 0, 8, -1, List.of(partial), List.of(MeshPart.of("nose", List.of(plain))))))));
    }

    @Test
    void everythingSurvivesTheRoundTrip() throws IOException {
        Map<String, List<MeshPart>> back = MeshCodec.read(MeshCodec.write(mesh()));

        assertEquals(Map.of("test", 1).keySet(), back.keySet());
        MeshPart root = back.get("test").getFirst();

        assertEquals("root", root.name());
        assertEquals(1, root.x(), 1e-4);
        assertEquals(24.016f, root.y(), 1e-4);
        assertEquals(-3, root.z(), 1e-4);
        assertEquals(0.25f, root.xRot(), 1e-4);
        assertEquals(-0.5f, root.yRot(), 1e-4);
        assertEquals(0.75f, root.zRot(), 1e-4);
        assertEquals(1.5f, root.xScale(), 1e-4);
        assertEquals(2f, root.zScale(), 1e-4);
        assertEquals(1, root.cubes().size());

        MeshPart head = root.children().getFirst();
        assertEquals("head", head.name());
        assertTrue(head.head(), "the part that turns is recognized by its name on the way back in");
        assertEquals(8, head.y(), 1e-4);
        assertEquals(1, head.children().size(), "and a part nested two deep is still there");

        MeshCube cube = head.cubes().getFirst();
        assertEquals(-1, cube.minX(), 1e-4);
        assertEquals(6, cube.maxZ(), 1e-4);
        assertNull(cube.face(Direction.SOUTH), "a side the model does not draw stays undrawn");
        assertNotNull(cube.face(Direction.NORTH));
        assertEquals(0.5f, cube.face(Direction.NORTH)[MeshCube.corner(true, false) * 2], 1e-5, "corner coordinates keep their corners");
        assertEquals(0.75f, cube.face(Direction.NORTH)[MeshCube.corner(false, true) * 2 + 1], 1e-5);
    }

    /**
     * A head nested inside a head comes back turning once.
     *
     * <p>Which is the shape the equines have - a {@code head} inside a {@code head_parts}, both answering to the name -
     * so a reader that decides from a name alone turns a donkey's head twice. That was already fixed in the extractor
     * and stayed broken in game for exactly this reason: the cache re-decided it on the way back in.
     */
    @Test
    void aHeadInsideAHeadOnlyTurnsOnce() throws IOException {
        MeshCube cube = MeshCube.plain(0, 0, 0, 1, 1, 1);
        MeshPart inner = MeshPart.at("head", 0, 0, 0, List.of(cube), List.of());
        MeshPart outer = MeshPart.at("head_parts", 0, -4, -12, List.of(cube), List.of(inner));

        Map<String, List<MeshPart>> back = MeshCodec.read(MeshCodec.write(
                Map.of("equine", List.of(MeshPart.at("root", 0, 20, 0, List.of(), List.of(outer))))));

        MeshPart read = back.get("equine").getFirst().children().getFirst();
        assertTrue(read.head(), "the outermost part that answers to the name takes the head rotation");
        assertFalse(read.children().getFirst().head(), "the one nested under it does not take it again");
    }

    /** An identity pose is left out of the document to keep it small, so it has to come back as identity. */
    @Test
    void aPartWithNoPoseComesBackWithTheIdentityOne() throws IOException {
        Map<String, List<MeshPart>> back = MeshCodec.read(MeshCodec.write(
                Map.of("test", List.of(MeshPart.of("body", List.of(MeshCube.plain(0, 0, 0, 1, 1, 1)))))));

        MeshPart part = back.get("test").getFirst();
        assertEquals(0, part.x(), 1e-6);
        assertEquals(1, part.xScale(), 1e-6, "not zero, which would collapse everything under it");
        assertEquals(1, part.yScale(), 1e-6);
        assertEquals(1, part.zScale(), 1e-6);
    }

    @Test
    void aDocumentFromAnotherVersionIsRefusedRatherThanMisread() throws IOException {
        assertEquals(Map.of(), MeshCodec.read(("{\"version\":" + (MeshCodec.VERSION + 1) + ",\"meshes\":{\"test\":[]}}").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rubbishReadsAsNoMeshesRatherThanThrowing() throws IOException {
        assertEquals(Map.of(), MeshCodec.read("not json at all".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Map.of(), MeshCodec.read(new byte[0]));

        byte[] whole = MeshCodec.write(mesh());
        assertEquals(Map.of(), MeshCodec.read(java.util.Arrays.copyOf(whole, whole.length / 2)), "truncated");
    }
}
