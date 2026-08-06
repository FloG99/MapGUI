package de.flog99.mapgui.render;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracted meshes as json, so they can ride along in the texture cache instead of being baked again per server
 * start - or, worse, committed.
 *
 * <p>Streamed rather than mapped onto classes: this is a few hundred thousand numbers, the shape is fixed, and a
 * reflective mapper would want the records to be public and mutable to do it. Written short-keyed and rounded, both
 * of which are about size - full float precision on a coordinate that is always a quarter of a pixel spends nine
 * characters saying {@code 0.020833332836628}.
 */
final class MeshCodec {

    /** Bumped when the shape below changes, so a cache written by an older one is refused rather than misread. */
    static final int VERSION = 1;

    /** Positions are quarter pixels and UVs are texels over a few hundred, so neither needs more than this. */
    private static final double POSITION_STEP = 1e4;
    private static final double COORDINATE_STEP = 1e6;

    private MeshCodec() {
    }

    static byte[] write(Map<String, List<MeshPart>> meshes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonWriter json = new JsonWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8))) {
            json.beginObject();
            json.name("version").value(VERSION);
            json.name("meshes").beginObject();
            for (Map.Entry<String, List<MeshPart>> mesh : meshes.entrySet()) {
                json.name(mesh.getKey());
                writeParts(json, mesh.getValue());
            }
            json.endObject();
            json.endObject();
        }
        return bytes.toByteArray();
    }

    /**
     * @return every mesh in the document, or empty when it is not one of ours - a truncated file, or one written
     *         by a version that laid it out differently. Empty means bounding boxes, which is a fallback and not a
     *         failure, so nothing here throws over content
     */
    static Map<String, List<MeshPart>> read(byte[] raw) {
        Map<String, List<MeshPart>> meshes = new LinkedHashMap<>();

        try (JsonReader json = new JsonReader(new StringReader(new String(raw, StandardCharsets.UTF_8)))) {
            json.beginObject();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "version" -> {
                        if (json.nextInt() != VERSION) return Map.of();
                    }
                    case "meshes" -> {
                        json.beginObject();
                        while (json.hasNext()) {
                            meshes.put(json.nextName(), MeshPart.withHeads(readParts(json)));
                        }
                        json.endObject();
                    }
                    default -> json.skipValue();
                }
            }
            json.endObject();
        } catch (IOException | RuntimeException e) {
            // Malformed, truncated, or numbers where objects should be. Either way there is nothing to draw with,
            // and nothing to draw with means bounding boxes rather than no capture.
            return Map.of();
        }

        return meshes;
    }

    private static void writeParts(JsonWriter json, List<MeshPart> parts) throws IOException {
        json.beginArray();
        for (MeshPart part : parts) {
            json.beginObject();
            json.name("n").value(part.name());

            if (part.x() != 0 || part.y() != 0 || part.z() != 0
                    || part.xRot() != 0 || part.yRot() != 0 || part.zRot() != 0
                    || part.xScale() != 1 || part.yScale() != 1 || part.zScale() != 1) {
                json.name("p").beginArray();
                for (float value : new float[]{part.x(), part.y(), part.z(), part.xRot(), part.yRot(), part.zRot(), part.xScale(), part.yScale(), part.zScale()}) {
                    json.value(round(value, POSITION_STEP));
                }
                json.endArray();
            }

            if (!part.cubes().isEmpty()) {
                json.name("c").beginArray();
                for (MeshCube cube : part.cubes()) {
                    writeCube(json, cube);
                }
                json.endArray();
            }

            if (!part.children().isEmpty()) {
                json.name("k");
                writeParts(json, part.children());
            }
            json.endObject();
        }
        json.endArray();
    }

    private static void writeCube(JsonWriter json, MeshCube cube) throws IOException {
        json.beginObject();
        json.name("b").beginArray();
        for (float value : new float[]{cube.minX(), cube.minY(), cube.minZ(), cube.maxX(), cube.maxY(), cube.maxZ()}) {
            json.value(round(value, POSITION_STEP));
        }
        json.endArray();

        json.name("f").beginArray();
        for (Direction side : Direction.values()) {
            float[] corners = cube.face(side);
            if (corners == null) {
                json.nullValue();
                continue;
            }
            json.beginArray();
            for (float value : corners) {
                json.value(round(value, COORDINATE_STEP));
            }
            json.endArray();
        }
        json.endArray();
        json.endObject();
    }

    private static List<MeshPart> readParts(JsonReader json) throws IOException {
        List<MeshPart> parts = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            String name = "";
            float[] pose = {0, 0, 0, 0, 0, 0, 1, 1, 1};
            List<MeshCube> cubes = List.of();
            List<MeshPart> children = List.of();

            json.beginObject();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "n" -> name = json.nextString();
                    case "p" -> pose = numbers(json, 9);
                    case "c" -> cubes = readCubes(json);
                    case "k" -> children = readParts(json);
                    default -> json.skipValue();
                }
            }
            json.endObject();

            // False here and resolved once the tree is whole: which part turns with the head is not a property of
            // its name, and a subtree cannot see whether something above it already claimed the rotation.
            parts.add(new MeshPart(name, false,
                    pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6], pose[7], pose[8],
                    cubes, children));
        }
        json.endArray();
        return List.copyOf(parts);
    }

    private static List<MeshCube> readCubes(JsonReader json) throws IOException {
        List<MeshCube> cubes = new ArrayList<>();
        json.beginArray();
        while (json.hasNext()) {
            float[] bounds = {0, 0, 0, 0, 0, 0};
            float[][] faces = new float[6][];

            json.beginObject();
            while (json.hasNext()) {
                switch (json.nextName()) {
                    case "b" -> bounds = numbers(json, 6);
                    case "f" -> {
                        json.beginArray();
                        for (int side = 0; side < 6 && json.hasNext(); side++) {
                            if (json.peek() == JsonToken.NULL) {
                                json.nextNull();
                            } else {
                                faces[side] = numbers(json, 8);
                            }
                        }
                        json.endArray();
                    }
                    default -> json.skipValue();
                }
            }
            json.endObject();

            cubes.add(new MeshCube(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5], faces));
        }
        json.endArray();
        return List.copyOf(cubes);
    }

    /** A fixed-length array of numbers, padded rather than refused if the document is short of them. */
    private static float[] numbers(JsonReader json, int length) throws IOException {
        float[] values = new float[length];
        json.beginArray();
        for (int i = 0; json.hasNext(); i++) {
            float value = (float) json.nextDouble();
            if (i < length) {
                values[i] = value;
            }
        }
        json.endArray();
        return values;
    }

    private static double round(float value, double step) {
        return Math.round(value * step) / step;
    }
}
