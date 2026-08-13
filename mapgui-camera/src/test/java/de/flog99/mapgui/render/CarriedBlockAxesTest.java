package de.flog99.mapgui.render;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which way up a block hung on a mob's joint ends up.
 *
 * <p>Two frames are crossed and only one is obvious: a mesh is a half circle about Z from the space a client chain is
 * written in, and a block's mesh a half circle about <b>Y</b> from the model it states. Use the first rule alone and
 * every carried block is upside down and back to front. Measured rather than argued about - it was wrong twice.
 */
class CarriedBlockAxesTest {

    @TempDir
    Path dir;

    private final Map<String, String> files = new LinkedHashMap<>();
    private final List<AutoCloseable> open = new ArrayList<>();

    /** Closed after every test: an open ZipFile locks its file on Windows, and @TempDir then cannot delete it. */
    @AfterEach
    void closeStacks() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    /** A two pixel box in the block's own +X +Y +Z corner, so each of its axes is told apart afterwards. */
    private static final String CORNER = """
            {"textures": {"skin": "block/corner"}, "elements": [{"from": [14, 14, 14], "to": [16, 16, 16],
             "faces": {"down": {"texture": "#skin"}, "up": {"texture": "#skin"},
                       "north": {"texture": "#skin"}, "south": {"texture": "#skin"},
                       "west": {"texture": "#skin"}, "east": {"texture": "#skin"}}}]}
            """;

    /** Where that corner ends up, in entity pixels off the joint, once the block is hung on one with this turn. */
    private float[] corner(float[] turn) throws IOException {
        files.put(AssetStack.BLOCK_MODELS + "corner.json", CORNER);
        files.put(AssetStack.BLOCKSTATES + "corner.json", "{\"variants\": {\"\": {\"model\": \"minecraft:block/corner\"}}}");
        files.put("assets/minecraft/textures/block/corner.png", "corner");

        Map<String, String> all = new LinkedHashMap<>(Zips.completeBase("26.2"));
        all.putAll(files);
        AssetStack stack = AssetStack.of(List.of(), AssetPack.open(Zips.write(dir.resolve("pack-" + open.size() + ".zip"), all)), "26.2");
        open.add(stack);

        TextureAtlas atlas = new TextureAtlas(stack);
        BlockModels models = new BlockModels(stack, texture -> BakedState.Alpha.OPAQUE);
        ItemDefinitions definitions = new ItemDefinitions(stack, new BiomeColors(stack, atlas));
        ItemModels items = new ItemModels(atlas, new BlockItems(models, definitions), models, new ItemPoses(stack, definitions));

        List<EntitySnapshot> layers = items.displayed("minecraft:corner", "minecraft:corner");
        assertEquals(1, layers.size(), "one texture, one layer");

        EntityModel hung = layers.getFirst().model()
                .onJoint(new EntityModel.Joint(0, 0, 0, Turns.none()), new ItemPoses.Pose(new float[]{0, 0, 0}, turn, 1), false);
        return middle(hung);
    }

    /** A block placed with no turn of its own keeps its up, and has its other two axes the other way round. */
    @Test
    void aBlockMeshIsItsModelTurnedAboutY() throws IOException {
        float[] at = corner(new float[]{0, 0, 0});

        assertEquals(-7, at[0], 0.01, "the model's east is the mesh's west");
        assertEquals(7, at[1], 0.01, "up is still up");
        assertEquals(-7, at[2], 0.01, "and the model's south is the mesh's north");
    }

    /** A golem's poppy: the client's quarter circle about X across both frames, laying the block's top forwards. */
    @Test
    void aQuarterTurnBackAboutXLaysABlocksTopForwards() throws IOException {
        float[] at = corner(new float[]{(float) Math.toRadians(-90), 0, 0});

        assertEquals(-7, at[0], 0.01, "nothing about X changes");
        assertEquals(-7, at[1], 0.01, "what was the block's north is now underneath");
        assertEquals(-7, at[2], 0.01, "and what was its top is now in front");
    }

    /** The middle of every box in a model, walked down the tree the way the tracer walks it. */
    private static float[] middle(EntityModel model) {
        float[] box = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        reach(model.parts(), 0, 0, 0, Turns.none(), box);
        return new float[]{(box[0] + box[1]) / 2, (box[2] + box[3]) / 2, (box[4] + box[5]) / 2};
    }

    private static void reach(List<MeshPart> parts, float x, float y, float z, float[] turn, float[] box) {
        for (MeshPart part : parts) {
            float[] offset = Turns.apply(turn, part.x(), part.y(), part.z());
            float atX = x + offset[0];
            float atY = y + offset[1];
            float atZ = z + offset[2];
            float[] turned = Turns.times(turn, Turns.part(part.xRot(), part.yRot(), part.zRot()));

            for (MeshCube cube : part.cubes()) {
                for (float cx : new float[]{cube.minX(), cube.maxX()}) {
                    for (float cy : new float[]{cube.minY(), cube.maxY()}) {
                        for (float cz : new float[]{cube.minZ(), cube.maxZ()}) {
                            float[] point = Turns.apply(turned, cx * part.xScale(), cy * part.yScale(), cz * part.zScale());
                            box[0] = Math.min(box[0], atX + point[0]);
                            box[1] = Math.max(box[1], atX + point[0]);
                            box[2] = Math.min(box[2], atY + point[1]);
                            box[3] = Math.max(box[3], atY + point[1]);
                            box[4] = Math.min(box[4], atZ + point[2]);
                            box[5] = Math.max(box[5], atZ + point[2]);
                        }
                    }
                }
            }
            reach(part.children(), atX, atY, atZ, turned, box);
        }
    }
}
