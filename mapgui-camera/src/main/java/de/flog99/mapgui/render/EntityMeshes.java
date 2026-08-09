package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Which vanilla mesh each entity type is drawn from, and which texture that mesh indexes.
 *
 * <p>A line per entity rather than a model per entity: the geometry is baked out of the client jar by
 * {@link MeshExtractor}, so all this has to know is the name of the class that builds it.
 *
 * <p>A type not in this table, or one whose mesh would not bake, has no model and the caller draws its bounding box
 * instead. Nothing here guesses - a wrong shape is worse than no shape.
 */
final class EntityMeshes {

    /**
     * One vanilla layer to bake.
     *
     * @param type           a class under {@code net.minecraft.client.model}
     * @param factory        its static mesh factory, named rather than searched for - see {@link MeshExtractor}
     * @param textureWidth   0 when the factory returns a {@code LayerDefinition}, which states its own size. Only the
     *                       few handing back a bare {@code MeshDefinition} need one, and a wrong size reads the whole
     *                       model off the wrong part of the texture
     * @param numbers        what to pass its float parameters, in order, where zero is not what the client passes - a
     *                       donkey and a mule are the equine mesh at 0.87 and 0.92
     * @param scale          what the client scales the whole mesh by when it registers this layer, or 0 for none. A
     *                       husk is a zombie at 1.0625 and a cave spider a spider at 0.7
     * @param space          which space this model is built in, which is decided by what its renderer does to it
     * @param state          fields to set on the render state before the client poses this mesh, as field name to
     *                       enum constant. For the handful of poses that are a property of the individual rather than
     *                       of the species - an illager's arms are crossed or holding a crossbow, and nothing about
     *                       the model says which
     */
    record Layer(String type, String factory, int textureWidth, int textureHeight, float[] numbers, float scale,
                 String component, String pose, Space space, Map<String, String> state) {
    }

    /**
     * The three ways a model reaches the world, which nothing about a class name tells you - an armor stand and an
     * end crystal are both {@code object.*} and are drawn by different kinds of renderer.
     */
    enum Space {

        /**
         * Built hanging downward from the neck and drawn upside down: {@code LivingEntityRenderer} flips it and
         * translates it 1.501 blocks, which is what stands it on the ground. Almost everything.
         */
        MOB,

        /**
         * Built the right way up already, because a bare {@code EntityRenderer} does neither of those - an end
         * crystal, whose renderer scales it and drops it half a block and nothing else. Flipping one of these puts
         * its base on top of it.
         */
        ENTITY,

        /** The same, and measured from a block's corner rather than about its middle - a chest. */
        BLOCK;

        boolean flipped() {
            return this == MOB;
        }
    }

    /** Marks which space a mesh is built in, since the default is by far the commonest and stays unmarked. */
    private static final Map<String, Space> SPACES = Map.of("block:", Space.BLOCK, "entity:", Space.ENTITY);

    /** A named mesh: usually one layer, occasionally two that share a texture. */
    record Spec(String mesh, List<Layer> layers) {
    }

    /**
     * A shape, the texture it indexes, and how the client sizes it.
     *
     * @param scale what the entity's renderer multiplies the whole model by, for the handful that do
     * @param over  the layers worn over this one, empty for the ordinary mob. Each has its own cubes over its own
     *              texture, which is why they cannot just be more cubes on the model below
     */
    record Mob(EntityModel model, String texture, float scale, List<Mob> over) {
    }

    /**
     * The equipment meshes, asked for by name rather than hung off a species: one mesh is worn by everything humanoid
     * and one mob may wear four at once.
     *
     * <p>Every inflation is read off the client's own constants - {@code INNER_ARMOR_DEFORMATION} for the leggings
     * and {@code OUTER_ARMOR_DEFORMATION} for the rest. Guessing one puts a chestplate inside the chest.
     */
    private static final Map<String, String> EQUIPMENT = Map.ofEntries(
            // The four armor slots come out of one call, since vanilla builds them together off one body.
            Map.entry("armor/head", "HumanoidModel#createArmorMeshSet(0.5,1.0)~head@64x32"),
            Map.entry("armor/chest", "HumanoidModel#createArmorMeshSet(0.5,1.0)~chest@64x32"),
            Map.entry("armor/legs", "HumanoidModel#createArmorMeshSet(0.5,1.0)~legs@64x32"),
            Map.entry("armor/feet", "HumanoidModel#createArmorMeshSet(0.5,1.0)~feet@64x32"),

            Map.entry("horse_body", "animal.equine.AbstractEquineModel#createBodyMesh(0.1)@64x64"),
            Map.entry("horse_saddle", "animal.equine.EquineSaddleModel#createSaddleLayer"),
            Map.entry("donkey_saddle", "animal.equine.DonkeyModel#createSaddleLayer(0.87)"),
            Map.entry("mule_saddle", "animal.equine.DonkeyModel#createSaddleLayer(0.92)"),
            Map.entry("camel_saddle", "animal.camel.CamelSaddleModel#createSaddleLayer"),
            Map.entry("nautilus_saddle", "animal.nautilus.NautilusSaddleModel#createSaddleLayer"),
            Map.entry("pig_saddle", "animal.pig.PigModel#createBodyLayer(0.5)"),
            Map.entry("strider_saddle", "monster.strider.AdultStriderModel#createBodyLayer"),
            Map.entry("llama_body", "animal.llama.LlamaModel#createBodyLayer(0.5)"),
            Map.entry("wolf_body", "animal.wolf.AdultWolfModel#createBodyLayer(0.2)@64x32")
    );

    /** Installed with the mob meshes and empty until they are. A missing one draws nothing rather than failing. */
    private static volatile Map<String, EntityModel> equipment = Map.of();

    /** One equipment layer's shape, or null when this version would not give it up. */
    static EntityModel worn(String mesh) {
        return equipment.get(mesh);
    }

    /** The default factory name, which is what all but a dozen of the model classes call theirs. */
    private static final String DEFAULT_FACTORY = "createBodyLayer";

    private static final Table TABLE = table();

    /**
     * Installed once the asset layers are read, and replaced when they are reloaded. Static because
     * {@link EntitySnapshot#mob} is, and one asset stack is live at a time, so there is nothing to key by.
     */
    private static volatile Map<String, Mob> installed = Map.of();

    private EntityMeshes() {
    }

    /** Every entity named here, which is where {@link RendererCoats} starts looking for a coat table. */
    static Set<String> types() {
        return Set.copyOf(TABLE.types.keySet());
    }

    /** Every mesh the table names, deduplicated, for the extractor to bake. */
    static List<Spec> specs() {
        Map<String, Spec> specs = new LinkedHashMap<>();
        for (Entry entry : TABLE.types.values()) {
            specs.computeIfAbsent(entry.mesh, EntityMeshes::parse);
            if (entry.babyMesh != null) {
                specs.computeIfAbsent(entry.babyMesh, EntityMeshes::parse);
            }
            for (Worn worn : entry.over) {
                specs.computeIfAbsent(worn.mesh(), EntityMeshes::parse);
            }
            for (String mesh : entry.variants.values()) {
                specs.computeIfAbsent(mesh, EntityMeshes::parse);
            }
        }
        for (String mesh : EQUIPMENT.values()) {
            specs.computeIfAbsent(mesh, EntityMeshes::parse);
        }
        return List.copyOf(specs.values());
    }

    /**
     * Adopts freshly extracted geometry, or drops back to bounding boxes when handed nothing. A mesh the extraction
     * did not produce leaves its types on boxes, so a version whose sniffer moved package still draws every other mob.
     */
    static void install(Map<String, List<MeshPart>> meshes) {
        Map<String, Mob> mobs = new HashMap<>();
        TABLE.types.forEach((type, entry) -> {
            Mob adult = mob(meshes, entry, entry.mesh, entry.over);
            if (adult != null) {
                mobs.put(type, adult);
            }
            // No worn layer on the young one, or the grown fleece stands a sheep-sized coat around a lamb.
            Mob baby = entry.babyMesh == null ? null : mob(meshes, entry, entry.babyMesh, List.of());
            if (baby != null) {
                mobs.put(baby(type), baby);
            }
            entry.variants.forEach((variant, mesh) -> {
                Mob dressed = mob(meshes, entry, mesh, entry.over);
                if (dressed != null) {
                    mobs.put(variant(type, variant), dressed);
                }
            });
        });

        Map<String, EntityModel> worn = new HashMap<>();
        EQUIPMENT.forEach((name, mesh) -> {
            EntityModel model = model(meshes, mesh, 0, 0);
            if (model != null) {
                worn.put(name, model);
            }
        });

        Map<String, EntityModel> asBuilt = new HashMap<>();
        TABLE.types.forEach((type, entry) -> {
            EntityModel model = unplaced(meshes, entry.mesh);
            if (model != null) {
                asBuilt.put(type, model);
            }
        });

        installed = Map.copyOf(mobs);
        equipment = Map.copyOf(worn);
        unplaced = Map.copyOf(asBuilt);
    }

    /** The same geometry in the client's own model space, for the callers that place it themselves. */
    private static volatile Map<String, EntityModel> unplaced = Map.of();

    /**
     * One type's mesh as the client built it, before anything here stood it in the world.
     *
     * <p>Which is the space an item definition states its own transform against - see
     * {@link ItemDefinitions.Special}. Everything else wants {@link #of}, whose model is already where it goes.
     */
    static EntityModel asBuilt(String type) {
        return unplaced.get(type);
    }

    /**
     * The extracted parts with the standing-up undone: the ground lift off a mob mesh and the turn over with it, the
     * half block off a block entity's corner.
     *
     * <p>The flip comes off as a half circle about Z laid over the top rather than by rebuilding the tree. It is the
     * same rotation applied twice, so the geometry lands exactly back where the client had it - and only the geometry
     * matters here, since nothing poses one of these.
     */
    private static EntityModel unplaced(Map<String, List<MeshPart>> meshes, String mesh) {
        List<MeshPart> parts = meshes.get(mesh);
        if (parts == null) return null;

        Space space = spaceOf(mesh);
        float middle = space == Space.BLOCK ? MeshExtractor.HALF_BLOCK : 0;
        float lift = space == Space.MOB ? -MeshExtractor.GROUND : 0;

        List<MeshPart> moved = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            moved.add(part.moved(middle, lift, middle));
        }
        if (space != Space.MOB) return EntityModel.of(moved);

        return EntityModel.of(List.of(new MeshPart("unflipped", false, 0, 0, 0, 0, 0, (float) Math.PI,
                1, 1, 1, List.of(), List.copyOf(moved))));
    }

    /** Which space a mesh was built in, off the spec that named it. */
    private static Space spaceOf(String mesh) {
        return parse(mesh).layers().getFirst().space();
    }

    /**
     * Null for a type with no mesh, which is the caller's cue to fall back to a bounding box.
     *
     * @param variant the word the assets name this coat by, or null. One with no mesh of its own falls back to the
     *                species mesh: the cold cow and the temperate one differ in shape, the two mooshrooms do not
     * @param baby    checked before the variant, since vanilla builds one young mesh per species and hangs every
     *                variant's baby layer off it
     */
    static Mob of(String type, String variant, boolean baby) {
        Map<String, Mob> mobs = installed;
        if (baby) {
            Mob young = mobs.get(baby(type));
            if (young != null) return young;
        }
        if (variant != null) {
            Mob dressed = mobs.get(variant(type, variant));
            if (dressed != null) return dressed;
        }
        return mobs.get(type);
    }

    /** Whether {@code type} has a mesh of its own for a baby, as against being drawn as a small adult. */
    static boolean hasBaby(String type) {
        return installed.containsKey(baby(type));
    }

    private static Mob mob(Map<String, List<MeshPart>> meshes, Entry entry, String mesh, List<Worn> layers) {
        EntityModel model = model(meshes, mesh, entry.lift, entry.turn);
        if (model == null) return null;

        List<Mob> over = new ArrayList<>(layers.size());
        for (Worn worn : layers) {
            EntityModel drawn = model(meshes, worn.mesh(), entry.lift, entry.turn);
            if (drawn != null) {
                over.add(new Mob(drawn, worn.texture(), entry.scale, List.of()));
            }
        }
        return new Mob(model, entry.texture, entry.scale, List.copyOf(over));
    }

    /**
     * The extracted parts as a model, shifted if this entity's renderer does not stand it on the ground and turned
     * if it turns the whole thing by something other than the yaw.
     *
     * <p>The turn goes on a part above everything rather than into each root, so what is underneath keeps its own
     * pose - and it runs the other way round to the angle the client states, since it is stated before the half turn
     * about Z that a mesh comes out of here already carrying.
     */
    private static EntityModel model(Map<String, List<MeshPart>> meshes, String mesh, float lift, float turn) {
        List<MeshPart> parts = meshes.get(mesh);
        if (parts == null) return null;

        List<MeshPart> placed = parts;
        if (lift != 0) {
            placed = new ArrayList<>(parts.size());
            for (MeshPart part : parts) {
                placed.add(part.moved(0, lift, 0));
            }
        }
        if (turn != 0) {
            placed = List.of(new MeshPart("turned", false, 0, 0, 0, 0, (float) Math.toRadians(-turn), 0,
                    1, 1, 1, List.of(), List.copyOf(placed)));
        }
        return EntityModel.of(placed);
    }

    private static String baby(String type) {
        return type + "/baby";
    }

    private static String variant(String type, String variant) {
        return type + "#" + variant;
    }

    /**
     * A mesh name read as what it says: {@code package.Class}, with {@code #factory} when it is not the usual one,
     * {@code (numbers)} where zero will not do, {@code ~member} to pick one mesh out of a factory that returns a set,
     * {@code !class} for a mesh whose pose belongs to a different model class than its geometry,
     * {@code @<width>x<height>} for the few that hand back a bare mesh, {@code *scale} where the client registers it
     * scaled, {@code {field=CONSTANT}} where the pose depends on a field of the render state, and {@code +} to join
     * two layers into one.
     *
     * <p>The name is the specification rather than a label pointing at one, so two types that share a model share its
     * mesh by writing the same thing rather than by agreeing on a nickname.
     */
    private static Spec parse(String mesh) {
        List<Layer> layers = new ArrayList<>();
        for (String part : mesh.split("\\+")) {
            String name = part;
            int width = 0;
            int height = 0;
            float[] numbers = {};
            float scale = 0;

            // First, since what is inside the braces is the one part of a name that may hold anything at all.
            int brace = name.indexOf('{');
            Map<String, String> state = Map.of();
            if (brace >= 0) {
                Map<String, String> fields = new LinkedHashMap<>();
                for (String assignment : name.substring(brace + 1, name.indexOf('}')).split(",")) {
                    int equals = assignment.indexOf('=');
                    fields.put(assignment.substring(0, equals), assignment.substring(equals + 1));
                }
                state = Map.copyOf(fields);
                name = name.substring(0, brace);
            }

            // Which class stands the mob up, where that is not the class the mesh is built by - the exceptions are
            // the shared meshes, like a zombie drawn from the plain humanoid one but posed by ZombieModel.
            int poseAt = name.indexOf('!');
            String pose = null;
            if (poseAt >= 0) {
                pose = name.substring(poseAt + 1);
                name = name.substring(0, poseAt);
            }

            int star = name.indexOf('*');
            if (star >= 0) {
                scale = Float.parseFloat(name.substring(star + 1));
                name = name.substring(0, star);
            }

            int size = name.indexOf('@');
            if (size >= 0) {
                String[] both = name.substring(size + 1).split("x");
                width = Integer.parseInt(both[0]);
                height = Integer.parseInt(both[1]);
                name = name.substring(0, size);
            }

            int member = name.indexOf('~');
            String component = null;
            if (member >= 0) {
                component = name.substring(member + 1);
                name = name.substring(0, member);
            }

            int open = name.indexOf('(');
            if (open >= 0) {
                String[] written = name.substring(open + 1, name.indexOf(')')).split(",");
                numbers = new float[written.length];
                for (int i = 0; i < written.length; i++) {
                    numbers[i] = Float.parseFloat(written[i]);
                }
                name = name.substring(0, open);
            }

            Space space = Space.MOB;
            for (Map.Entry<String, Space> marked : SPACES.entrySet()) {
                if (name.startsWith(marked.getKey())) {
                    space = marked.getValue();
                    name = name.substring(marked.getKey().length());
                }
            }

            int hash = name.indexOf('#');
            String factory = hash < 0 ? DEFAULT_FACTORY : name.substring(hash + 1);
            layers.add(new Layer(hash < 0 ? name : name.substring(0, hash), factory, width, height, numbers, scale, component, pose, space, state));
        }
        return new Spec(mesh, List.copyOf(layers));
    }

    /**
     * The table: one entry per type, named by the temperate skin, with the coats that are a different <i>shape</i>
     * listed under it. A cold cow builds its horns as two turned parts rather than two cubes and off a different
     * patch of the texture, so drawn from the temperate mesh it loses them; a mooshroom really is a cow in another
     * color, which is why {@code also} keeps no variants.
     *
     * <p>Only adult coats are listed, because only adult coats have meshes - vanilla hangs every variant's baby off
     * one {@code BabyCowModel}. And a young animal only gets a mesh of its own where the assets carry a young texture
     * for it, since vanilla's young meshes are drawn on smaller textures and a mesh read off the wrong size is read
     * off the wrong part.
     *
     * <p>Where a type is missing it is missing on purpose: a mannequin has no skin to draw it with.
     */
    private static Table table() {
        return vehicles(new Table()
                // The humanoid undead. Zombies and huskies share vanilla's plain humanoid mesh; the drowned has
                // one of its own because its arms hang.
                .mob("zombie", "entity/zombie/zombie", "HumanoidModel#createMesh@64x64!monster.zombie.ZombieModel").baby("monster.zombie.BabyZombieModel")
                .mob("husk", "entity/zombie/husk", "HumanoidModel#createMesh@64x64*1.0625!monster.zombie.ZombieModel").baby("monster.zombie.BabyZombieModel")
                .mob("giant", "entity/zombie/zombie", "HumanoidModel#createMesh@64x64*6!monster.zombie.GiantZombieModel")
                // The drowned's outer skin is its own mesh grown a quarter pixel, off a texture of its own - the
                // seaweed and the bloat, which is most of what makes it look drowned rather than green.
                .mob("drowned", "entity/zombie/drowned", "monster.zombie.DrownedModel").baby("monster.zombie.BabyDrownedModel")
                .over("monster.zombie.DrownedModel#createBodyLayer(0.25)", "entity/zombie/drowned_outer_layer")
                // A stray's frost and a bogged's moss are the same idea: the plain humanoid mesh inflated, posed by
                // SkeletonModel, over an overlay texture. Only the inflation and the texture differ between them.
                .mob("skeleton", "entity/skeleton/skeleton", "monster.skeleton.SkeletonModel")
                .also("stray", "entity/skeleton/stray")
                .over(SKELETON_CLOTHES + "(0.25)@64x32!monster.skeleton.SkeletonModel", "entity/skeleton/stray_overlay")
                .mob("wither_skeleton", "entity/skeleton/wither_skeleton", "monster.skeleton.SkeletonModel*1.2")
                .mob("bogged", "entity/skeleton/bogged", "monster.skeleton.BoggedModel")
                .over(SKELETON_CLOTHES + "(0.2)@64x32!monster.skeleton.SkeletonModel", "entity/skeleton/bogged_overlay")
                .mob("parched", "entity/skeleton/parched", "monster.skeleton.SkeletonModel#createSingleModelDualBodyLayer")
                .mob("zombie_villager", "entity/zombie_villager/zombie_villager", "monster.zombie.ZombieVillagerModel").baby("monster.zombie.BabyZombieVillagerModel")
                .mob("piglin", "entity/piglin/piglin", "monster.piglin.AdultPiglinModel").baby("monster.piglin.BabyPiglinModel")
                .mob("piglin_brute", "entity/piglin/piglin_brute", "monster.piglin.AdultPiglinModel")
                .mob("zombified_piglin", "entity/piglin/zombified_piglin", "monster.piglin.AdultZombifiedPiglinModel").baby("monster.piglin.BabyZombifiedPiglinModel")

                // Villagers and illagers.
                .mob("villager", "entity/villager/villager", "npc.VillagerModel#createBodyModel@64x64*0.9375").baby("npc.BabyVillagerModel#createBodyModel@64x64*0.9375")
                .also("wandering_trader", "entity/wandering_trader/wandering_trader")
                // The illagers are one mesh in four skins, held in whichever pose that illager stands in when it is
                // doing nothing: arms folded for three of them, and a crossbow levelled for the one that carries one.
                // Which is a property of the individual rather than of the model, so it is stated rather than left
                // at the render state's own default - and that default is NEUTRAL, which is arms hanging.
                .mob("evoker", "entity/illager/evoker", ILLAGER + "{armPose=CROSSED}")
                .also("illusioner", "entity/illager/illusioner")
                .also("vindicator", "entity/illager/vindicator")
                .mob("pillager", "entity/illager/pillager", ILLAGER + "{armPose=CROSSBOW_HOLD}")
                .mob("witch", "entity/witch/witch", "monster.witch.WitchModel*0.9375")
                .mob("vex", "entity/illager/vex", "monster.vex.VexModel")
                .mob("ravager", "entity/illager/ravager", "monster.ravager.RavagerModel")

                // The rest of the hostiles.
                .mob("creeper", "entity/creeper/creeper", "monster.creeper.CreeperModel")
                .mob("enderman", "entity/enderman/enderman", "monster.enderman.EndermanModel")
                .mob("endermite", "entity/endermite/endermite", "monster.endermite.EndermiteModel")
                .mob("silverfish", "entity/silverfish/silverfish", "monster.silverfish.SilverfishModel")
                .mob("spider", "entity/spider/spider", "monster.spider.SpiderModel#createSpiderBodyLayer")
                .mob("cave_spider", "entity/spider/cave_spider", "monster.spider.SpiderModel#createSpiderBodyLayer*0.7")
                .mob("blaze", "entity/blaze/blaze", "monster.blaze.BlazeModel")
                .mob("breeze", "entity/breeze/breeze", "monster.breeze.BreezeModel")
                .mob("creaking", "entity/creaking/creaking", "monster.creaking.CreakingModel")
                .mob("ghast", "entity/ghast/ghast", "monster.ghast.GhastModel")
                .mob("happy_ghast", "entity/ghast/happy_ghast", "animal.ghast.HappyGhastModel")
                .mob("phantom", "entity/phantom/phantom", "monster.phantom.PhantomModel").lift(-21)
                .mob("guardian", "entity/guardian/guardian", "monster.guardian.GuardianModel")
                .mob("elder_guardian", "entity/guardian/guardian_elder", "monster.guardian.GuardianModel#createElderGuardianLayer")
                .mob("shulker", "entity/shulker/shulker", "monster.shulker.ShulkerModel")
                .mob("warden", "entity/warden/warden", "monster.warden.WardenModel")
                .mob("wither", "entity/wither/wither", "monster.wither.WitherBossModel").scale(2)
                .mob("ender_dragon", "entity/enderdragon/dragon", "monster.dragon.EnderDragonModel")
                .mob("hoglin", "entity/hoglin/hoglin", "monster.hoglin.HoglinModel").baby("monster.hoglin.BabyHoglinModel")
                .also("zoglin", "entity/hoglin/zoglin")
                .mob("strider", "entity/strider/strider", "monster.strider.AdultStriderModel").baby("monster.strider.BabyStriderModel")

                // A slime is a holed cube with a smaller one inside it, and both come off the one texture, so the
                // two layers are one mesh. A sulfur cube's do not, so its shell is a worn layer instead.
                .mob("slime", "entity/slime/slime", "monster.slime.SlimeModel#createInnerBodyLayer+monster.slime.SlimeModel#createOuterBodyLayer")
                .mob("magma_cube", "entity/slime/magmacube", "monster.slime.MagmaCubeModel")
                // Alone among the mob meshes, this one is built around its own middle rather than hung off the neck:
                // a 16px cube from -8 to 8 on every axis, where a slime's sits at 17 to 23 like everything else. So
                // the standard ground lift puts it a whole block up, and only the lift is wrong - it is a one block
                // cube and comes out as one at its own size.
                .mob("sulfur_cube", "entity/sulfur_cube/sulfur_cube_inner", "monster.slime.SulfurCubeModel#createInnerBodyLayer")
                .over("monster.slime.SulfurCubeModel#createOuterBodyLayer", "entity/sulfur_cube/sulfur_cube_outer")
                .lift(-16.016f)

                // The golems and the constructs.
                .mob("iron_golem", "entity/iron_golem/iron_golem", "animal.golem.IronGolemModel")
                .mob("snow_golem", "entity/snow_golem/snow_golem", "animal.golem.SnowGolemModel")
                .mob("copper_golem", "entity/copper_golem/copper_golem", "animal.golem.CopperGolemModel")
                // Block entities. Not mobs at all, but the same thing to everything downstream: a mesh, a texture
                // and a yaw. The texture named here is the plain chest, and the capture swaps in the one its wood
                // and its half actually wear.
                .mob("chest", "entity/chest/normal", "block:object.chest.ChestModel#createSingleBodyLayer")
                .mob("chest_left", "entity/chest/normal_left", "block:object.chest.ChestModel#createDoubleBodyLeftLayer")
                .mob("chest_right", "entity/chest/normal_right", "block:object.chest.ChestModel#createDoubleBodyRightLayer")

                .mob("armor_stand", "entity/armorstand/armorstand", "object.armorstand.ArmorStandModel")
                // Drawn by a bare EntityRenderer, so nothing turns it over or stands it on the ground. What that
                // renderer does do is double it and drop it half a block, and the drop is stated before the doubling
                // - so it is eight of the pixels this lift is measured in rather than sixteen.
                //
                // Flipped it turns wrongly rather than merely upside down, since a flip negates two of the three
                // part rotations and this one's pose has all three.
                .mob("end_crystal", "entity/end_crystal/end_crystal", "entity:object.crystal.EndCrystalModel")
                .scale(2).lift(-8)

                // The quadrupeds. Almost all have a baby mesh of their own, drawn on a texture of a different size,
                // which is exactly why the mesh has to come from the client instead of being scaled here.
                .mob("cow", "entity/cow/cow_temperate", "animal.cow.CowModel").baby("animal.cow.BabyCowModel")
                .variant("cold", "animal.cow.ColdCowModel").variant("warm", "animal.cow.WarmCowModel")
                .also("mooshroom", "entity/cow/mooshroom_red")
                .mob("pig", "entity/pig/pig_temperate", "animal.pig.PigModel").baby("animal.pig.BabyPigModel")
                .variant("cold", "animal.pig.ColdPigModel")
                .mob("sheep", "entity/sheep/sheep", "animal.sheep.SheepModel").baby("animal.sheep.BabySheepModel")
                .over("animal.sheep.SheepFurModel#createFurLayer", "entity/sheep/sheep_wool")
                .mob("chicken", "entity/chicken/chicken_temperate", "animal.chicken.AdultChickenModel").baby("animal.chicken.BabyChickenModel")
                .variant("cold", "animal.chicken.ColdChickenModel")
                .mob("wolf", "entity/wolf/wolf", "animal.wolf.AdultWolfModel#createBodyLayer@64x32").baby("animal.wolf.BabyWolfModel")
                .mob("cat", "entity/cat/cat_tabby", "animal.feline.AdultFelineModel#createBodyMesh@64x32*0.8").baby("animal.feline.BabyFelineModel#createBabyLayer")
                .mob("ocelot", "entity/cat/ocelot", "animal.feline.AdultFelineModel#createBodyMesh@64x32").baby("animal.feline.BabyFelineModel#createBabyLayer")
                .mob("fox", "entity/fox/fox", "animal.fox.AdultFoxModel").baby("animal.fox.BabyFoxModel")
                .mob("rabbit", "entity/rabbit/rabbit_brown", "animal.rabbit.AdultRabbitModel").baby("animal.rabbit.BabyRabbitModel")
                .mob("polar_bear", "entity/bear/polarbear", "animal.polarbear.PolarBearModel").baby("animal.polarbear.BabyPolarBearModel")
                .mob("panda", "entity/panda/panda", "animal.panda.PandaModel").baby("animal.panda.BabyPandaModel")
                .mob("goat", "entity/goat/goat", "animal.goat.GoatModel").baby("animal.goat.BabyGoatModel")
                .mob("llama", "entity/llama/llama_creamy", "animal.llama.LlamaModel").baby("animal.llama.BabyLlamaModel")
                .also("trader_llama", "entity/llama/llama_creamy")
                .mob("horse", "entity/horse/horse_brown", "animal.equine.AbstractEquineModel#createBodyMesh@64x64*1.1").baby("animal.equine.BabyHorseModel#createBabyMesh@64x64")
                .mob("skeleton_horse", "entity/horse/horse_skeleton", "animal.equine.AbstractEquineModel#createBodyMesh@64x64").baby("animal.equine.BabyHorseModel#createBabyMesh@64x64")
                .also("zombie_horse", "entity/horse/horse_zombie")
                .mob("donkey", "entity/horse/donkey", "animal.equine.DonkeyModel#createBodyLayer(0.87)").baby("animal.equine.BabyDonkeyModel#createBabyLayer")
                .mob("mule", "entity/horse/mule", "animal.equine.DonkeyModel#createBodyLayer(0.92)").baby("animal.equine.BabyDonkeyModel#createBabyLayer")
                .mob("camel", "entity/camel/camel", "animal.camel.AdultCamelModel").baby("animal.camel.BabyCamelModel")
                .mob("camel_husk", "entity/camel/camel_husk", "animal.camel.AdultCamelModel")
                .mob("sniffer", "entity/sniffer/sniffer", "animal.sniffer.SnifferModel")
                .mob("turtle", "entity/turtle/turtle", "animal.turtle.AdultTurtleModel").baby("animal.turtle.BabyTurtleModel")
                .mob("armadillo", "entity/armadillo/armadillo", "animal.armadillo.AdultArmadilloModel").baby("animal.armadillo.BabyArmadilloModel")

                // The small and the airborne.
                .mob("allay", "entity/allay/allay", "animal.allay.AllayModel")
                .mob("bat", "entity/bat/bat", "ambient.BatModel")
                .mob("bee", "entity/bee/bee", "animal.bee.AdultBeeModel").baby("animal.bee.BabyBeeModel")
                .mob("parrot", "entity/parrot/parrot_red_blue", "animal.parrot.ParrotModel")
                .mob("frog", "entity/frog/frog_temperate", "animal.frog.FrogModel")
                .mob("tadpole", "entity/tadpole/tadpole", "animal.frog.TadpoleModel")

                // The swimmers. A squid's renderer places it by hand rather than on the ground, so it is the one
                // that has to say how far - everything else above sits where the standard lift puts it.
                .mob("squid", "entity/squid/squid", "animal.squid.SquidModel").baby("animal.squid.BabySquidModel").lift(-11.2f)
                .also("glow_squid", "entity/squid/glow_squid")
                .mob("dolphin", "entity/dolphin/dolphin", "animal.dolphin.DolphinModel").baby("animal.dolphin.BabyDolphinModel")
                .mob("axolotl", "entity/axolotl/axolotl_lucy", "animal.axolotl.AdultAxolotlModel").baby("animal.axolotl.BabyAxolotlModel")
                .mob("cod", "entity/fish/cod", "animal.fish.CodModel")
                .mob("salmon", "entity/fish/salmon", "animal.fish.SalmonModel")
                .mob("pufferfish", "entity/fish/pufferfish", "animal.fish.PufferfishMidModel")
                // Two shapes and two textures, which the assets name a and b: half the patterns are drawn on a small
                // flat fish and half on a tall one. The word is the texture's own letter, so the coat rule reaches
                // the second texture by swapping it - and the mesh follows the same word.
                .mob("tropical_fish", "entity/fish/tropical_a", "animal.fish.TropicalFishSmallModel")
                .variant("b", "animal.fish.TropicalFishLargeModel")
                .mob("nautilus", "entity/nautilus/nautilus", "animal.nautilus.NautilusModel").baby("animal.nautilus.NautilusModel#createBabyBodyLayer")
                .mob("zombie_nautilus", "entity/nautilus/zombie_nautilus", "animal.nautilus.NautilusModel")

                // The heads, named as the blocks are. Block entities like a chest but flipped like a mob:
                // SkullBlockRenderer stands one on the block it is in and does nothing else, so the whole of vanilla's
                // ground offset comes off again.
                //
                // Four meshes for seven heads, and the split is the texture rather than the shape: the same
                // eight-pixel cube read off a 32x16 sheet for the ones wearing a mob's face, and off a 64x64 one -
                // with the hat layer a skin carries - for the two unwrapped like a player.
                .mob("skeleton_skull", "entity/skeleton/skeleton", "object.skull.SkullModel#createMobHeadLayer").lift(SKULL_LIFT)
                .also("wither_skeleton_skull", "entity/skeleton/wither_skeleton")
                .also("creeper_head", "entity/creeper/creeper")
                .mob("player_head", "entity/player/wide/steve", "object.skull.SkullModel#createHumanoidHeadLayer").lift(SKULL_LIFT)
                .also("zombie_head", "entity/zombie/zombie")
                .mob("piglin_head", "entity/piglin/piglin", "object.skull.PiglinHeadModel#createHeadModel@64x64").lift(SKULL_LIFT)
                .mob("dragon_head", "entity/enderdragon/dragon", "object.skull.DragonHeadModel#createHeadLayer").lift(SKULL_LIFT)

                // The rest of the block entities the client keeps a built-in model for. Every one of these is turned
                // over by its renderer the way a mob is - {@code scale(1, -1, -1)} rather than a mob's
                // {@code (-1, -1, 1)}, which is the same flip with a half circle about Y on top of it, so they are
                // mob meshes with that half circle in their turn. What differs is how far up each is stood, and the
                // shape of the block they sit in is the whole of it.
                .mob("shulker_box", "entity/shulker/shulker", "monster.shulker.ShulkerModel#createBoxLayer")
                .lift(BLOCK_ENTITY_LIFT).turn(BLOCK_ENTITY_TURN)
                // A conduit is the one of these its renderer does not turn over at all - it translates half a block
                // and spins it, and nothing else - so it is built the right way up like an end crystal.
                .mob("conduit", "entity/conduit/base", "entity:renderer.blockentity.ConduitRenderer#createShellLayer")
                .lift(HALF_BLOCK)
                // A bell is the one whose renderer does nothing to it at all - no flip, no lift, not even a turn for
                // the way the block faces - so it is a block entity in the plainest sense, measured from the block's
                // own corner like a chest. What holds it up is the block model and is drawn already; this is the
                // bell itself, which is what was missing from every one of them.
                .mob("bell", "entity/bell/bell_body", "block:object.bell.BellModel")

                // A decorated pot, in two meshes because it is drawn off two textures: the clay body, and the four
                // sides, each of which wears whichever sherd was pressed into it. Measured from the block's corner
                // like a chest, since its renderer neither flips it nor lifts it - only turns it to face.
                //
                // Both are built by DecoratedPotRenderer, whose static fields map every sherd item to a sprite and so
                // reach the item registry. That is what {@link MeshExtractor#bootstrap} is for, and the only thing in
                // this table that asks for it.
                .mob("decorated_pot", "entity/decorated_pot/decorated_pot_base",
                        "block:renderer.blockentity.DecoratedPotRenderer#createBaseLayer")
                .mob("decorated_pot_sides", "entity/decorated_pot/decorated_pot_side",
                        "block:renderer.blockentity.DecoratedPotRenderer#createSidesLayer")

                // A copper golem statue, which is the golem's own mesh held in one of four poses - and the poses are
                // four layers of vanilla's own rather than angles written here.
                //
                // Built the right way up by its own model class: CopperGolemStatueModel#setupAnim stands the mesh on
                // the block floor and turns it over with a half circle about Z, which is the same turn a mob's
                // renderer reaches with scale(-1, -1, 1). So it is stated as an entity mesh posed by that class, and
                // the standing up comes out of the client rather than out of a lift here.
                .mob("copper_golem_statue", "entity/copper_golem/copper_golem",
                        "entity:animal.golem.CopperGolemModel#createBodyLayer!object.statue.CopperGolemStatueModel")
                .variant("running", "entity:animal.golem.CopperGolemModel#createRunningPoseBodyLayer!object.statue.CopperGolemStatueModel")
                .variant("sitting", "entity:animal.golem.CopperGolemModel#createSittingPoseBodyLayer!object.statue.CopperGolemStatueModel")
                .variant("star", "entity:animal.golem.CopperGolemModel#createStarPoseBodyLayer!object.statue.CopperGolemStatueModel")

                // The book over an enchanting table. Drawn the right way up and posed by its own model, which at rest
                // means shut - the openness and the two page flips are all zero when nobody is standing there.
                .mob("book", "entity/enchantment/enchanting_table_book", "entity:object.book.BookModel")

                // A banner is a pole and a crossbar with a separate cloth hung off it, and the two are separate
                // meshes because the cloth is the only part a dye colors and a pattern is drawn on. A wall banner has
                // no pole, which is what the flag the factory takes is asking.
                //
                // Alone among these its renderer lifts it nowhere at all - it flips the mesh about the block's middle
                // and leaves it hanging - so the whole of vanilla's ground offset comes back off, and the two thirds
                // it is drawn at is the scale rather than anything about the mesh.
                .mob("banner", "entity/banner/banner_base", "object.banner.BannerModel(1)")
                .lift(SKULL_LIFT).turn(BLOCK_ENTITY_TURN).scale(BANNER_SCALE)
                .mob("banner_flag", "entity/banner/base", "object.banner.BannerFlagModel#createFlagLayer(1)")
                .lift(SKULL_LIFT).turn(BLOCK_ENTITY_TURN).scale(BANNER_SCALE)
                .mob("wall_banner", "entity/banner/banner_base", "object.banner.BannerModel(0)")
                .lift(SKULL_LIFT).turn(BLOCK_ENTITY_TURN).scale(BANNER_SCALE)
                .mob("wall_banner_flag", "entity/banner/base", "object.banner.BannerFlagModel#createFlagLayer(0)")
                .lift(SKULL_LIFT).turn(BLOCK_ENTITY_TURN).scale(BANNER_SCALE)

                // Two the client draws in code that are items rather than blocks, and so are only ever placed by a
                // definition's own transform - which is why neither states a lift here.
                .mob("shield", "entity/shield/shield_base_nopattern", "object.equipment.ShieldModel#createLayer")
                .mob("trident", "entity/trident/trident", "object.projectile.TridentModel#createLayer"));
    }

    /**
     * What a skull's lift has to undo, in entity pixels: all of it. A mob is turned over and then translated 1.501
     * blocks to stand it on the ground, and a skull is turned over and translated nowhere at all.
     */
    private static final float SKULL_LIFT = -1.501f * 16;

    /**
     * What {@code SkeletonClothingLayer} is handed: the plain humanoid mesh, inflated, on the skeleton's own texture
     * size and posed as a skeleton. A stray's frost and a bogged's moss differ only in how far it is inflated.
     */
    private static final String SKELETON_CLOTHES = "HumanoidModel#createMesh";

    /** The one illager mesh, at the size its renderer registers it. Four mobs, four skins, four arm poses. */
    private static final String ILLAGER = "monster.illager.IllagerModel*0.9375";

    /**
     * And what a vehicle's lift comes to. Both renderers flip the model like a mob, so the mesh arrives with that
     * same 1.501 blocks in it, and both stand it 0.375 blocks up instead.
     */
    private static final float VEHICLE_LIFT = 0.375f * 16 - 1.501f * 16;

    /**
     * And what the block entities turned over by {@code scale(1, -1, -1)} come to, which is very nearly nothing.
     *
     * <p>Their renderers flip the mesh about the middle of the block and then lift it back to the block's floor, and
     * the two together land it a block and a half up - the same place a mob's 1.501 puts one, bar the half-thousandth
     * vanilla adds there to keep coincident surfaces from fighting.
     *
     * <p>Stated as the whole 1.5 rather than as zero because that is what it is measuring, and because getting it
     * wrong is invisible in the shape and obvious in the picture: at one block instead of one and a half, a shulker
     * box sits half a block into the floor. The half block is not the placing either - a turn about the block's middle
     * says where the mesh turns and moves it nowhere.
     */
    private static final float BLOCK_ENTITY_LIFT = 1.5f * 16 - 1.501f * 16;

    /**
     * The half circle between the two flips. A mob's renderer turns its model over with {@code scale(-1, -1, 1)} and
     * these use {@code scale(1, -1, -1)}, and the two differ by exactly a half turn about Y - so an extracted mesh,
     * which arrives already carrying the first, wants the second put on top of it.
     */
    private static final float BLOCK_ENTITY_TURN = 180;

    /** Half a block up, in entity pixels, which is where a conduit's renderer puts it and nothing else does. */
    private static final float HALF_BLOCK = 8;

    /** What a banner's renderer draws it at, which is the one of these that is a size rather than a place. */
    private static final float BANNER_SCALE = 2 / 3f;

    /**
     * A boat is built along its side and its renderer turns it a quarter circle after flipping it, which is the one
     * thing here that no mesh and no yaw carries.
     */
    private static final float BOAT_TURN = 90;

    /**
     * The boats and the minecarts, which are one shape each over a great many entity types - nine woods of boat and
     * seven kinds of cart - so they are looped rather than written out.
     *
     * <p>A minecart's own texture serves every kind of it: what makes one a chest minecart is the block it displays,
     * which the client draws from that block's own model and {@link EntityMeshes} knows nothing about.
     */
    private static Table vehicles(Table table) {
        table.mob("minecart", MINECART_TEXTURE, "object.cart.MinecartModel").lift(VEHICLE_LIFT);
        for (String kind : List.of("chest", "furnace", "tnt", "hopper", "spawner", "command_block")) {
            table.also(kind + "_minecart", MINECART_TEXTURE);
        }

        // The nine woods a boat comes in. Bamboo is not one of them: it is a raft, which is a different shape.
        // Written here rather than as a constant because this runs from a static initializer, and a field declared
        // below the table it builds is still null when the table is built.
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "pale_oak", "mangrove")) {
            table.mob(wood + "_boat", "entity/boat/" + wood, "object.boat.BoatModel#createBoatModel")
                    .lift(VEHICLE_LIFT).turn(BOAT_TURN)
                    .mob(wood + "_chest_boat", "entity/chest_boat/" + wood, "object.boat.BoatModel#createChestBoatModel")
                    .lift(VEHICLE_LIFT).turn(BOAT_TURN);
        }

        return table.mob("bamboo_raft", "entity/boat/bamboo", "object.boat.RaftModel#createRaftModel")
                .lift(VEHICLE_LIFT).turn(BOAT_TURN)
                .mob("bamboo_chest_raft", "entity/chest_boat/bamboo", "object.boat.RaftModel#createChestRaftModel")
                .lift(VEHICLE_LIFT).turn(BOAT_TURN);
    }

    private static final String MINECART_TEXTURE = "entity/minecart/minecart";

    /** One layer a mob's renderer adds over its skin: its own mesh, over its own texture. */
    private record Worn(String mesh, String texture) {
    }

    /**
     * @param over     the layers this mob's renderer draws over it, in the order it adds them. Empty for most
     * @param lift     how far off vanilla's standard ground offset this mesh sits, in entity pixels
     * @param turn     a quarter circle or so about Y that this entity's renderer applies on top of its yaw, in
     *                 degrees. Zero for everything but the boats, whose model is built along their side
     * @param variants a mesh per coat that is genuinely a different shape, keyed by the word the assets name it by.
     *                 Empty for the great majority, which either have no variants or wear them as colors
     */
    private record Entry(String texture, String mesh, String babyMesh, List<Worn> over, float scale, float lift,
                         float turn, Map<String, String> variants) {
    }

    /** Chained rather than positional, so a line says only what is unusual about its mob. */
    private static final class Table {

        private final Map<String, Entry> types = new LinkedHashMap<>();
        private String last;

        Table mob(String type, String texture, String mesh) {
            types.put(type, new Entry(texture, mesh, null, List.of(), 1, 0, 0, Map.of()));
            last = type;
            return this;
        }

        /**
         * The same mesh in another skin. The variants are deliberately not carried over: a mooshroom has no cold form
         * to inherit one for, and carrying them would leave {@code cold} on a type whose word is only ever
         * {@code red} or {@code brown}.
         *
         * <p>Its worn layers are, because they belong to the mesh: a stray is a skeleton in a coat, and the coat is
         * the one thing that makes it look like a stray. A skin that wants none says so with {@link #bare}.
         */
        Table also(String type, String texture) {
            Entry from = types.get(last);
            types.put(type, new Entry(texture, from.mesh, from.babyMesh, from.over, from.scale, from.lift, from.turn, Map.of()));
            last = type;
            return this;
        }

        Table baby(String mesh) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, mesh, entry.over, entry.scale, entry.lift, entry.turn, entry.variants));
        }

        /** A coat of this species that vanilla builds a mesh of its own for. */
        Table variant(String variant, String mesh) {
            return replace(entry -> {
                Map<String, String> variants = new LinkedHashMap<>(entry.variants);
                variants.put(variant, mesh);
                return new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.scale, entry.lift, entry.turn, Map.copyOf(variants));
            });
        }

        /** A layer this mob's renderer adds over its skin. Repeatable, for the few that wear more than one. */
        Table over(String mesh, String texture) {
            return replace(entry -> {
                List<Worn> over = new ArrayList<>(entry.over);
                over.add(new Worn(mesh, texture));
                return new Entry(entry.texture, entry.mesh, entry.babyMesh, List.copyOf(over), entry.scale, entry.lift, entry.turn, entry.variants);
            });
        }

        Table scale(float scale) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, scale, entry.lift, entry.turn, entry.variants));
        }

        Table lift(float pixels) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.scale, pixels, entry.turn, entry.variants));
        }

        Table turn(float degrees) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.scale, entry.lift, degrees, entry.variants));
        }

        private Table replace(UnaryOperator<Entry> change) {
            types.put(last, change.apply(types.get(last)));
            return this;
        }
    }
}
