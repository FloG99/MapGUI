package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.ItemPoses;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Art;
import org.bukkit.NamespacedKey;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the entities in view out of the server, in the same tick as the blocks.
 *
 * <p>Everything the trace needs is read here and nothing is held: no {@code Entity} reference survives, because
 * the trace runs off the main thread and an entity can die, move or unload while it does.
 *
 * <p>Which texture an individual wears is {@link MobTextures}, and what it wears over that is {@link MobEquipment}.
 * What is left here is which entities are in the picture and how each one stands.
 */
final class EntityCapture {

    /** Past this an entity is a couple of pixels on a map, and roughly where the client stops sending them. */
    private static final double MAX_DISTANCE = 64;

    /** A cap, so a mob farm in frame cannot turn one capture into thousands of box tests. Nearest first. */
    private static final int MAX_ENTITIES = 48;

    private EntityCapture() {
    }

    static List<EntitySnapshot> take(Player viewer, Location eye, SkinCache skins, MobAssets assets, FramedMaps maps, boolean includeSelf) {
        List<Entity> nearby = new ArrayList<>(viewer.getWorld().getNearbyEntities(eye, MAX_DISTANCE, MAX_DISTANCE, MAX_DISTANCE));
        nearby.sort((left, right) -> Double.compare(left.getLocation().distanceSquared(eye), right.getLocation().distanceSquared(eye)));

        List<EntitySnapshot> snapshots = new ArrayList<>();
        for (Entity entity : nearby) {
            if (snapshots.size() >= MAX_ENTITIES) break;
            if (entity.equals(viewer) && !includeSelf) {
                continue;
            }
            // An ender dragon is nine entities, and every one of its eight hitboxes answers "ender_dragon" - so drawn
            // as they come they are nine dragons stacked. The client treats the parts as hitboxes, so this does too.
            if (entity instanceof ComplexEntityPart) {
                continue;
            }

            snapshots.addAll(snapshotsOf(entity, skins, assets, maps));
        }

        return snapshots;
    }

    /**
     * The snapshots one entity is drawn from, nearly always one and empty for an entity that is not drawn at all.
     * More than one only for a mob wearing a second layer, since a snapshot samples one texture.
     */
    private static List<EntitySnapshot> snapshotsOf(Entity entity, SkinCache skins, MobAssets assets, FramedMaps maps) {
        Location at = entity.getLocation();
        String type = entity.getType().name().toLowerCase(Locale.ROOT);

        if (entity instanceof Player player) {
            List<EntitySnapshot> drawn = playerSnapshots(player, at, type, skins, assets);
            if (drawn != null) return drawn;
        } else if (entity instanceof Painting hung) {
            List<EntitySnapshot> drawn = painting(hung, at, assets);
            if (!drawn.isEmpty()) return drawn;
        } else if (entity instanceof ItemFrame frame) {
            List<EntitySnapshot> drawn = itemFrame(frame, at, assets, skins, maps);
            if (!drawn.isEmpty()) return drawn;
        } else if (entity instanceof Item dropped) {
            for (String id : ItemIds.of(dropped.getItemStack())) {
                List<EntitySnapshot> sprite = assets.items().dropped(
                        id, at.getX(), at.getY() + bob(dropped), at.getZ(), spin(dropped));
                // Empty when neither a sprite nor a block model resolved, which falls through to the box below.
                if (!sprite.isEmpty()) {
                    return skins.faced(sprite, dropped.getItemStack());
                }
            }
        } else {
            List<EntitySnapshot> drawn = mobSnapshots(entity, at, type, assets, skins);
            if (drawn != null) return drawn;
        }

        return boundingBox(entity, at, type, assets);
    }

    /** Null until the skin has come down, which takes a capture or two - better than somebody else's face. */
    private static List<EntitySnapshot> playerSnapshots(Player player, Location at, String type, SkinCache skins, MobAssets assets) {
        String skin = skins.nameFor(player);
        if (skin == null) return null;

        EntitySnapshot body = EntitySnapshot.player(at.getX(), at.getY(), at.getZ(), bodyYaw(player), at.getYaw(),
                at.getPitch(), skins.isSlim(player), skins.layersOf(player), skin, player.isSneaking());

        List<EntitySnapshot> drawn = new ArrayList<>();
        drawn.add(body);
        drawn.addAll(MobEquipment.wornBy(player, body, type, assets, skins));
        return drawn;
    }

    /** Null for a type with no authored shape, which is the caller's cue to fall back to a bounding box. */
    private static List<EntitySnapshot> mobSnapshots(Entity entity, Location at, String type, MobAssets assets, SkinCache skins) {
        String variant = MobTextures.variantOf(entity, type);
        float body = bodyYaw(entity);
        EntitySnapshot authored = EntitySnapshot.mob(
                type, variant,
                at.getX(), at.getY(), at.getZ(),
                body, headYaw(type, body, at.getYaw() + halfTurn(entity)), at.getPitch(),
                scaleOf(entity), isBaby(entity)
        );
        if (authored == null) return null;

        String skin = MobTextures.skinOf(entity, type, variant, authored.texture(), isBaby(entity), assets);
        Arms arms = armsOf(entity, at);
        EntitySnapshot bare = swimming(entity, hideUnworn(entity, authored.texture(skin)), type);
        EntitySnapshot dressed = arms == null ? bare : arms.on(bare);

        List<EntitySnapshot> drawn = new ArrayList<>();
        drawn.add(dressed);
        EntitySnapshot layer = wornLayer(entity, dressed, type, variant);
        if (layer != null) {
            drawn.add(layer);
        }
        drawn.addAll(MobEquipment.wornBy(entity, dressed, type, assets, skins));
        drawn.addAll(carried(entity, dressed, assets));
        return drawn;
    }

    /**
     * The block a minecart is carrying, which is the whole of what makes one a chest or a tnt minecart: every kind of
     * cart is the same mesh in the same texture, and what tells them apart is the block the cart states.
     *
     * <p>The block's own state rather than its item, which is how the client resolves it and not a detail: a hopper's
     * item model is a flat icon and its block model is the funnel, and a cart carrying the first is a cart carrying a
     * picture.
     */
    private static List<EntitySnapshot> carried(Entity entity, EntitySnapshot cart, MobAssets assets) {
        if (!(entity instanceof Minecart minecart)) return List.of();

        BlockData block = minecart.getDisplayBlockData();
        if (block == null || block.getMaterial().isAir()) return List.of();

        List<EntitySnapshot> drawn = new ArrayList<>();
        for (EntitySnapshot layer : assets.items().displayed(block.getAsString(), block.getMaterial().getKey().asString())) {
            EntitySnapshot inside = EntitySnapshot.inCart(cart, layer, minecart.getDisplayBlockOffset());
            if (inside != null) {
                drawn.add(inside);
            }
        }
        return drawn;
    }

    /**
     * A painting, at its variant's own size and wearing that variant's own picture.
     *
     * <p>Centred on where the entity stands, which is where the client centres it - a two-by-one painting hangs half a
     * block either side of that point rather than starting at it.
     *
     * <p>The picture is looked up under the variant's name and nothing is guessed: a datapack painting whose png is
     * not in the assets falls through to the bounding box, which is at least the right rectangle.
     */
    private static List<EntitySnapshot> painting(Painting hung, Location at, MobAssets assets) {
        Art art = hung.getArt();
        if (art == null) return List.of();

        // Through the registry rather than off the constant, since the constants are on their way out and a datapack
        // painting is not one of them anyway.
        NamespacedKey variant = RegistryAccess.registryAccess().getRegistry(RegistryKey.PAINTING_VARIANT).getKey(art);
        if (variant == null) return List.of();

        String picture = "painting/" + variant.getKey();
        if (!assets.atlas().has(picture) || !assets.atlas().has(PAINTING_BACK)) return List.of();

        return EntitySnapshot.painting(at.getX(), at.getY(), at.getZ(), facingYaw(hung.getFacing()),
                art.getBlockWidth(), art.getBlockHeight(), picture, PAINTING_BACK);
    }

    /** The planks every painting is nailed to, which is one texture for all of them. */
    private static final String PAINTING_BACK = "painting/back";

    /**
     * An item frame: the frame itself, and whatever is hanging in it.
     *
     * <p>Its renderer pushes the whole thing 0.46875 blocks out along the face it is on, centres the frame's own
     * block model on that point and turns it to face out - and then hangs the item at the front of the backplate, at
     * half size, turned by whichever eighth of a circle the frame has been clicked round to.
     *
     * <p>A frame on a floor or a ceiling is tipped a quarter circle on top of that, and there the trace is left
     * unturned and the tip carried in the model - which is what puts the two rotations in the client's own order,
     * since the trace's is always outermost.
     *
     * <p>The yaw is a chest's rather than a painting's, because what is being placed is a block model: those arrive a
     * half circle about Y from where their json states them, and that half circle is exactly the difference between
     * the two conventions.
     *
     * <p>A framed map fills the frame rather than sitting in it as an item does, and gets the frame vanilla keeps for
     * one - the model with the border a map fills. Its picture is read out of the world's own saved map data, since
     * that is the only place it exists.
     */
    private static List<EntitySnapshot> itemFrame(ItemFrame frame, Location at, MobAssets assets, SkinCache skins, FramedMaps maps) {
        BlockFace facing = frame.getFacing();
        double x = at.getX() + facing.getModX() * FRAME_OUT;
        double y = at.getY() + facing.getModY() * FRAME_OUT;
        double z = at.getZ() + facing.getModZ() * FRAME_OUT;

        float yaw = facingYaw(facing) - HALF_TURN;
        float tipped = (float) Math.toRadians(-QUARTER * facing.getModY());

        ItemStack held = frame.getItem();
        boolean map = held != null && held.getType() == Material.FILLED_MAP;

        List<EntitySnapshot> drawn = new ArrayList<>();
        if (frame.isVisible()) {
            String model = "block/" + (frame.getType() == EntityType.GLOW_ITEM_FRAME ? "glow_item_frame" : "item_frame")
                    + (map ? "_map" : "");
            for (EntitySnapshot layer : assets.items().modelled(model)) {
                drawn.add(EntitySnapshot.frame(x, y, z, yaw, layer).tipped(tipped));
            }
        }
        if (map) {
            EntitySnapshot picture = framedMap(frame, held, x, y, z, yaw, tipped, assets, maps);
            if (picture != null) {
                drawn.add(picture);
            }
        } else {
            drawn.addAll(framed(frame, held, x, y, z, yaw, tipped, assets, skins));
        }
        return List.copyOf(drawn);
    }

    /**
     * The map's own pixels, or null when the world will not give them up - which leaves the frame and no picture.
     *
     * <p>A map turns in quarters where an item turns in eighths, which is vanilla's own {@code rotation % 4}: there
     * are only four ways up a map can be read.
     */
    private static EntitySnapshot framedMap(ItemFrame frame, ItemStack held, double x, double y, double z,
                                            float yaw, float tipped, MobAssets assets, FramedMaps maps) {
        MapMeta meta = held.getItemMeta() instanceof MapMeta mapped ? mapped : null;
        MapView view = meta == null ? null : meta.getMapView();
        if (view == null) return null;

        String texture = maps.textureOf(view.getId(), assets.atlas());
        if (texture == null) return null;

        float spin = (float) Math.toRadians(frame.getRotation().ordinal() % QUARTERS * QUARTER);
        return EntitySnapshot.framedMap(x, y, z, yaw, texture, spin).tipped(tipped);
    }

    /** How many ways up a map can be read, against the eight an item can be turned to. */
    private static final int QUARTERS = 4;

    /** How far out of its block the client pushes a frame, in blocks, and the quarter circle a flat one is tipped. */
    private static final double FRAME_OUT = 0.46875;

    private static final float QUARTER = 90;

    /** The half circle a block model arrives carrying, which is the whole of what separates the two yaw rules. */
    private static final float HALF_TURN = 180;

    /** What is in the frame, or nothing at all - which is an empty frame and much the commonest. */
    private static List<EntitySnapshot> framed(ItemFrame frame, ItemStack held, double x, double y, double z,
                                               float yaw, float tipped, MobAssets assets, SkinCache skins) {
        if (held == null || held.isEmpty()) return List.of();

        float spin = (float) Math.toRadians(frame.getRotation().ordinal() * EIGHTH);
        for (String id : ItemIds.of(held)) {
            List<EntitySnapshot> layers = assets.items().held(id);
            if (layers.isEmpty()) continue;

            List<EntitySnapshot> hung = new ArrayList<>(layers.size());
            for (EntitySnapshot layer : skins.faced(layers, held)) {
                hung.add(EntitySnapshot.framed(x, y, z, yaw, layer,
                        assets.items().stated(id, ItemPoses.IN_FRAME), spin).tipped(tipped));
            }
            return List.copyOf(hung);
        }
        return List.of();
    }

    /** An eighth of a circle, which is the whole range a frame can be turned to. */
    private static final float EIGHTH = 360 / 8f;

    /**
     * The yaw that points a hung thing's front along a block face.
     *
     * <p>Vanilla turns a painting by {@code 180 - 90 * facing.get2DDataValue()}, which for the four horizontal faces
     * is the facing's own yaw - and the trace turns a model by {@code -180 - yaw}, so the yaw to hand it is that
     * facing's yaw and not the half turn a chest wants.
     */
    private static float facingYaw(BlockFace facing) {
        Vector direction = facing.getDirection();
        return (float) -Math.toDegrees(Math.atan2(direction.getX(), direction.getZ()));
    }

    /** The last resort, and empty for anything the assets carry no texture for at all. */
    private static List<EntitySnapshot> boundingBox(Entity entity, Location at, String type, MobAssets assets) {
        String authored = MobTextures.boundingBox(type, assets.atlas());
        String texture = MobTextures.skinOf(entity, type, MobTextures.variantOf(entity, type), authored, isBaby(entity), assets);

        BoundingBox box = entity.getBoundingBox();
        if (texture == null || box.getVolume() <= 0) return List.of();

        return List.of(EntitySnapshot.box(
                at.getX(), at.getY(), at.getZ(),
                bodyYaw(entity), at.getYaw(),
                Math.max(box.getWidthX(), box.getWidthZ()), box.getHeight(),
                texture
        ));
    }

    /** Whether this is a sulfur cube with something inside it, which is drawn in place of its inner shell. */
    private static boolean holding(Entity entity) {
        if (entity.getType() != EntityType.SULFUR_CUBE || !(entity instanceof LivingEntity living)) return false;

        EntityEquipment worn = living.getEquipment();
        ItemStack inside = worn == null ? null : worn.getItem(EquipmentSlot.BODY);
        return inside != null && !inside.isEmpty();
    }

    /**
     * Parts the mesh carries but this animal is not wearing. A donkey, mule and llama build their two panniers into
     * the mesh, and the client hides them unless the animal really is carrying a chest.
     */
    private static EntitySnapshot hideUnworn(Entity entity, EntitySnapshot mob) {
        if (entity instanceof ChestedHorse chested && !chested.isCarryingChest()) {
            return mob.without("left_chest", "right_chest");
        }
        // A sulfur cube's inner shell is what whatever has been put inside it replaces, rather than something the
        // block hides behind: SulfurCubeInnerLayer draws one or the other and never both.
        if (holding(entity)) {
            return mob.without("cube");
        }
        // The bedrock slab under an end crystal, which one placed to respawn the dragon does not have - only the
        // four standing on the obsidian pillars do, and the entity carries the flag either way.
        if (entity instanceof EnderCrystal crystal && !crystal.isShowingBottom()) {
            return mob.without("base");
        }

        return mob;
    }

    /**
     * The second layer this mob wears, or null for the great majority that wear none. A sheep is the special case:
     * its fleece comes off one white texture that vanilla colors per animal, so the dye travels with the layer, and
     * a shorn sheep is a layer left out rather than a color.
     */
    private static EntitySnapshot wornLayer(Entity entity, EntitySnapshot base, String type, String variant) {
        if (entity instanceof Sheep sheep) {
            DyeColor dye = sheep.getColor();
            return EntitySnapshot.fleece(base, type, variant, sheep.isSheared(),
                    dye == null ? null : dye.name().toLowerCase(Locale.ROOT));
        }

        return EntitySnapshot.over(base, type, variant);
    }

    /** What {@code ArmPose.BOW_AND_ARROW} levels both arms to, and how far it swings the off arm clear. */
    private static final float AIMING_ARMS = (float) -(Math.PI / 2);

    private static final float ARMS_INWARD = 0.1f;

    private static final float AIMING_OFF_ARM_OUTWARD = 0.4f;

    /**
     * The one pose a mesh cannot carry: an archer levelling its bow. Everything else a mob does while standing still
     * is baked in by the client's own {@code setupAnim}, but this is not a property of the species - the same
     * skeleton has its arms down until it has something to shoot at.
     *
     * <p>The arms follow the <b>head</b>, which is the client's own coupling: a skeleton shoots at what it is looking
     * at, so arms levelled down the body point its bow a foot to one side of you.
     *
     * @return null for every mob that is not aiming, which is nearly all of them
     */
    private static Arms armsOf(Entity entity, Location at) {
        if (!(entity instanceof LivingEntity living) || !(entity instanceof Mob mob) || !mob.isAggressive()) return null;

        EntityEquipment worn = living.getEquipment();
        if (worn == null || !aiming(worn.getItemInMainHand())) return null;

        float turned = (float) Math.toRadians(wrapped(at.getYaw() - bodyYaw(entity)));
        float raised = (float) Math.toRadians(at.getPitch());

        // The arm holding the bow turns slightly inward and the other swings a further 0.4 clear of the string.
        float holding = -ARMS_INWARD + turned;
        float clear = ARMS_INWARD + AIMING_OFF_ARM_OUTWARD + turned;
        return MobEquipment.leftHanded(entity)
                ? new Arms(AIMING_ARMS + raised, -clear, -holding)
                : new Arms(AIMING_ARMS + raised, holding, clear);
    }

    /**
     * One arm pose, stated in the client's signs and applied in this module's. X and Y rotations run the other way in
     * the space a mesh is kept in, as {@link de.flog99.mapgui.render.MeshPart} says, so the flip is here.
     */
    private record Arms(float xRot, float rightYRot, float leftYRot) {

        EntitySnapshot on(EntitySnapshot snapshot) {
            return snapshot.posed("right_arm", -xRot, -rightYRot, 0).posed("left_arm", -xRot, -leftYRot, 0);
        }
    }

    /** A bow or a crossbow, which are the two the client levels an arm for. */
    private static boolean aiming(ItemStack mainHand) {
        if (mainHand == null || mainHand.isEmpty()) return false;

        return mainHand.getType() == Material.BOW || mainHand.getType() == Material.CROSSBOW;
    }

    /**
     * How far the client lets a mob's head turn from its body. The server stores a head yaw that can lead by 75
     * degrees and most models draw whatever it says, but {@code AbstractEquineModel} clamps it to twenty - without
     * which a donkey stares straight at the camera while the animal in front of you has barely moved its head.
     */
    private static final Map<String, Float> HEAD_TURN_LIMIT = Map.of(
            "horse", 20f,
            "donkey", 20f,
            "mule", 20f,
            "skeleton_horse", 20f,
            "zombie_horse", 20f,

            // A dragon's head does not follow its head yaw at all. EnderDragonModel lays the neck and the head along
            // the path the dragon has just flown, out of a flight history the client keeps for itself and the server
            // never sends - so the only honest angle here is none, which draws the head straight ahead the way one
            // flying level is drawn. Turned by the difference instead it swung most of a right angle, and the wrong
            // way, because a dragon's body yaw is not kept in step with the yaw it carries.
            "ender_dragon", 0f
    );

    static float headYaw(String type, float bodyYaw, float headYaw) {
        Float limit = HEAD_TURN_LIMIT.get(type);
        if (limit == null) return headYaw;

        return bodyYaw + Math.clamp(wrapped(headYaw - bodyYaw), -limit, limit);
    }

    /** An angle brought into -180..180, so a head crossing north is not read as having spun right round. */
    static float wrapped(float degrees) {
        float turn = degrees % 360;
        if (turn >= 180) return turn - 360;
        if (turn < -180) return turn + 360;
        return turn;
    }

    private static final Set<String> SQUIDS = Set.of("squid", "glow_squid");

    /** Under a block a second a squid is drifting rather than swimming, and the client's angle is unknowable. */
    private static final double SQUID_DRIFTING = 0.05;

    /** The client turns a squid about a point half a block up, which for a baby its own half scale takes care of. */
    private static final float SQUID_PIVOT = 8;

    /**
     * The mobs the client tilts bodily. A squid points along whatever it is jetting along, by the client's own
     * arithmetic on its velocity, and a fish out of water lies on its side.
     *
     * <p>One departure: the client keeps a slow average of that angle and nothing on the server carries it, so a
     * squid that is barely moving is drawn upright rather than at whatever an unseen average happens to hold.
     */
    private static EntitySnapshot swimming(Entity entity, EntitySnapshot mob, String type) {
        if (SQUIDS.contains(type)) {
            Vector speed = entity.getVelocity();
            double across = Math.hypot(speed.getX(), speed.getZ());
            if (across + Math.abs(speed.getY()) < SQUID_DRIFTING) return mob;

            return mob.tilted((float) Math.atan2(across, speed.getY()), 0, SQUID_PIVOT);
        }

        if (entity instanceof Fish && !entity.isInWater()) {
            return mob.tilted(0, (float) Math.toRadians(90), 0);
        }

        return mob;
    }

    /**
     * How much bigger than its authored size an entity is drawn. A baby is half of its parent, where the game also
     * enlarges the head - but its hitbox is exactly half, and half of the right shape beats a full-sized calf.
     */
    private static float scaleOf(Entity entity) {
        if (entity instanceof Slime slime) return slime.getSize();

        return isBaby(entity) ? 0.5f : 1f;
    }

    private static boolean isBaby(Entity entity) {
        return entity instanceof Ageable ageable && !ageable.isAdult();
    }

    /**
     * How far round a dropped item has turned, the way the client turns it: its age in radians over twenty ticks.
     *
     * <p>Offset per item so a pile does not spin as one lump. Vanilla's own offset is a random drawn when the entity
     * is created and never sent, so the id is hashed for one instead - the phase is arbitrary either way, and what
     * matters is that two items in the same pile disagree and that each keeps its own between frames.
     */
    private static float spin(Item dropped) {
        // Negated: the trace turns a model the opposite way round to the client's own Y rotation, so the unnegated
        // angle spins every dropped item backwards.
        return -(float) Math.toDegrees(dropped.getTicksLived() / 20.0 + phase(dropped));
    }

    /**
     * How far off the ground it is riding, in blocks. The client's own sine, twice as quick as the spin and never
     * negative, so an item hovers just clear of the floor rather than sinking through it.
     */
    private static double bob(Item dropped) {
        return Math.sin(dropped.getTicksLived() / 10.0 + phase(dropped)) * 0.1 + 0.1;
    }

    /**
     * The offset that keeps two items in a pile from turning and rising as one lump.
     *
     * <p>Vanilla draws one at random when the entity is created and never sends it, so the id is hashed for one
     * instead. Shared between the spin and the bob because vanilla shares it: an item is at the top of its rise at
     * a different point of its turn depending which item it is.
     */
    private static float phase(Item dropped) {
        return (dropped.getUniqueId().hashCode() % 628) / 100f;
    }

    /**
     * Where the body faces, which is not where the head does. {@code Location#getYaw} is the head on a living entity,
     * and using it here makes a mob look at you with its whole torso.
     */
    private static float bodyYaw(Entity entity) {
        if (UNTURNED.contains(entity.getType())) return NOT_TURNED;

        float yaw = entity instanceof LivingEntity living ? living.getBodyYaw() : entity.getLocation().getYaw();
        return yaw + halfTurn(entity);
    }

    /**
     * Entities their renderer never turns by the yaw they carry: an end crystal spins in its own animation and its
     * slab stays square to the world, so taking the entity's yaw tilts the slab by whatever that happens to be.
     */
    private static final Set<EntityType> UNTURNED = Set.of(EntityType.END_CRYSTAL);

    /** What to hand the trace for those, which turns a model by {@code -180 - yaw} and so leaves this one alone. */
    private static final float NOT_TURNED = -180;

    /**
     * Half a turn for the two drawn by a bare {@code EntityRenderer}, which turns a model by {@code -yaw} where
     * {@code LivingEntityRenderer} turns it by {@code 180 - yaw}. The trace carries that 180 for everything, so
     * without this a dragon flies tail first.
     *
     * <p>It has to reach the head as well as the body. The trace turns a head by the difference between the two, so
     * turning only the body leaves the head pointing exactly backwards.
     */
    private static float halfTurn(Entity entity) {
        return TURNED_ABOUT.contains(entity.getType()) ? 180 : 0;
    }

    /** See {@link #halfTurn}. Checked against the renderer rather than guessed at. */
    private static final Set<EntityType> TURNED_ABOUT = Set.of(EntityType.ENDER_DRAGON);
}
