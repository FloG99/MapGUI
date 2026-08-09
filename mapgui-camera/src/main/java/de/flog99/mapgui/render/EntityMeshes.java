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
     */
    record Layer(String type, String factory, int textureWidth, int textureHeight, float[] numbers, float scale,
                 String component, String pose, Space space) {
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
     * @param over  a second layer worn over this one, or null for the ordinary mob. Its own cubes over its own
     *              texture, which is why it cannot just be more cubes on the model below it
     */
    record Mob(EntityModel model, String texture, float scale, Mob over) {
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
            for (String mesh : List.of(entry.mesh, entry.babyMesh == null ? entry.mesh : entry.babyMesh,
                    entry.over == null ? entry.mesh : entry.over)) {
                specs.computeIfAbsent(mesh, EntityMeshes::parse);
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
            Mob baby = entry.babyMesh == null ? null : mob(meshes, entry, entry.babyMesh, null);
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
            EntityModel model = model(meshes, mesh, 0);
            if (model != null) {
                worn.put(name, model);
            }
        });

        installed = Map.copyOf(mobs);
        equipment = Map.copyOf(worn);
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

    private static Mob mob(Map<String, List<MeshPart>> meshes, Entry entry, String mesh, String overMesh) {
        EntityModel model = model(meshes, mesh, entry.lift);
        if (model == null) return null;

        EntityModel worn = overMesh == null ? null : model(meshes, overMesh, entry.lift);
        Mob over = worn == null ? null : new Mob(worn, entry.overTexture, entry.scale, null);
        return new Mob(model, entry.texture, entry.scale, over);
    }

    /** The extracted parts as a model, shifted if this entity's renderer does not stand it on the ground. */
    private static EntityModel model(Map<String, List<MeshPart>> meshes, String mesh, float lift) {
        List<MeshPart> parts = meshes.get(mesh);
        if (parts == null) return null;
        if (lift == 0) return EntityModel.of(parts);

        List<MeshPart> lifted = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            lifted.add(part.moved(0, lift, 0));
        }
        return EntityModel.of(lifted);
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
     * scaled, and {@code +} to join two layers into one.
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
            layers.add(new Layer(hash < 0 ? name : name.substring(0, hash), factory, width, height, numbers, scale, component, pose, space));
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
     * <p>Where a type is missing it is missing on purpose: a mannequin has no skin to draw it with, and boats and
     * minecarts are placed by renderers that translate them somewhere of their own.
     */
    private static Table table() {
        return new Table()
                // The humanoid undead. Zombies and huskies share vanilla's plain humanoid mesh; the drowned has
                // one of its own because its arms hang.
                .mob("zombie", "entity/zombie/zombie", "HumanoidModel#createMesh@64x64!monster.zombie.ZombieModel").baby("monster.zombie.BabyZombieModel")
                .mob("husk", "entity/zombie/husk", "HumanoidModel#createMesh@64x64*1.0625!monster.zombie.ZombieModel").baby("monster.zombie.BabyZombieModel")
                .mob("giant", "entity/zombie/zombie", "HumanoidModel#createMesh@64x64*6!monster.zombie.GiantZombieModel")
                .mob("drowned", "entity/zombie/drowned", "monster.zombie.DrownedModel").baby("monster.zombie.BabyDrownedModel")
                .mob("skeleton", "entity/skeleton/skeleton", "monster.skeleton.SkeletonModel")
                .also("stray", "entity/skeleton/stray")
                .mob("wither_skeleton", "entity/skeleton/wither_skeleton", "monster.skeleton.SkeletonModel*1.2")
                .mob("bogged", "entity/skeleton/bogged", "monster.skeleton.BoggedModel")
                .mob("parched", "entity/skeleton/parched", "monster.skeleton.SkeletonModel#createSingleModelDualBodyLayer")
                .mob("zombie_villager", "entity/zombie_villager/zombie_villager", "monster.zombie.ZombieVillagerModel").baby("monster.zombie.BabyZombieVillagerModel")
                .mob("piglin", "entity/piglin/piglin", "monster.piglin.AdultPiglinModel").baby("monster.piglin.BabyPiglinModel")
                .mob("piglin_brute", "entity/piglin/piglin_brute", "monster.piglin.AdultPiglinModel")
                .mob("zombified_piglin", "entity/piglin/zombified_piglin", "monster.piglin.AdultZombifiedPiglinModel").baby("monster.piglin.BabyZombifiedPiglinModel")

                // Villagers and illagers.
                .mob("villager", "entity/villager/villager", "npc.VillagerModel#createBodyModel@64x64*0.9375").baby("npc.BabyVillagerModel#createBodyModel@64x64*0.9375")
                .also("wandering_trader", "entity/wandering_trader/wandering_trader")
                .mob("evoker", "entity/illager/evoker", "monster.illager.IllagerModel*0.9375")
                .also("illusioner", "entity/illager/illusioner")
                .also("pillager", "entity/illager/pillager")
                .also("vindicator", "entity/illager/vindicator")
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
                .mob("tropical_fish", "entity/fish/tropical_a", "animal.fish.TropicalFishSmallModel")
                .mob("nautilus", "entity/nautilus/nautilus", "animal.nautilus.NautilusModel").baby("animal.nautilus.NautilusModel#createBabyBodyLayer")
                .mob("zombie_nautilus", "entity/nautilus/zombie_nautilus", "animal.nautilus.NautilusModel");
    }

    /**
     * @param lift     how far off vanilla's standard ground offset this mesh sits, in entity pixels
     * @param variants a mesh per coat that is genuinely a different shape, keyed by the word the assets name it by.
     *                 Empty for the great majority, which either have no variants or wear them as colors
     */
    private record Entry(String texture, String mesh, String babyMesh, String over, String overTexture, float scale, float lift,
                         Map<String, String> variants) {
    }

    /** Chained rather than positional, so a line says only what is unusual about its mob. */
    private static final class Table {

        private final Map<String, Entry> types = new LinkedHashMap<>();
        private String last;

        Table mob(String type, String texture, String mesh) {
            types.put(type, new Entry(texture, mesh, null, null, null, 1, 0, Map.of()));
            last = type;
            return this;
        }

        /**
         * The same mesh in another skin. The variants are deliberately not carried over: a mooshroom has no cold form
         * to inherit one for, and carrying them would leave {@code cold} on a type whose word is only ever
         * {@code red} or {@code brown}.
         */
        Table also(String type, String texture) {
            Entry from = types.get(last);
            types.put(type, new Entry(texture, from.mesh, from.babyMesh, from.over, from.overTexture, from.scale, from.lift, Map.of()));
            return this;
        }

        Table baby(String mesh) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, mesh, entry.over, entry.overTexture, entry.scale, entry.lift, entry.variants));
        }

        /** A coat of this species that vanilla builds a mesh of its own for. */
        Table variant(String variant, String mesh) {
            return replace(entry -> {
                Map<String, String> variants = new LinkedHashMap<>(entry.variants);
                variants.put(variant, mesh);
                return new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.overTexture, entry.scale, entry.lift, Map.copyOf(variants));
            });
        }

        Table over(String mesh, String texture) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, mesh, texture, entry.scale, entry.lift, entry.variants));
        }

        Table scale(float scale) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.overTexture, scale, entry.lift, entry.variants));
        }

        Table lift(float pixels) {
            return replace(entry -> new Entry(entry.texture, entry.mesh, entry.babyMesh, entry.over, entry.overTexture, entry.scale, pixels, entry.variants));
        }

        private Table replace(UnaryOperator<Entry> change) {
            types.put(last, change.apply(types.get(last)));
            return this;
        }
    }
}
