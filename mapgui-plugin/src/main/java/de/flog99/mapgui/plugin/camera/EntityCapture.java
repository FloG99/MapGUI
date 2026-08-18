package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.camera.EntityDetails;
import de.flog99.mapgui.render.CameraView;
import de.flog99.mapgui.render.ChunkFrustum;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.ItemPoses;
import de.flog99.mapgui.render.Tints;
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
import org.bukkit.entity.Bogged;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fish;
import org.bukkit.entity.IronGolem;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies the entities in view out of the server, in the same tick as the blocks.
 *
 * <p>Everything the trace needs is read here and no {@code Entity} reference survives, because the trace runs off the
 * main thread and an entity can die, move or unload while it does. What can outlive a capture is a mob's built
 * shape, held by id in {@link MobCache} - snapshots, which is the same copied-out data the trace already works from.
 *
 * <p>Which texture an individual wears is {@link MobTextures}, and what it wears over that is {@link MobEquipment}.
 * What is left here is which entities are in the picture and how each one stands.
 */
final class EntityCapture {

    /**
     * How far what is drawn for an entity reaches past its own point, in blocks. Generous on purpose: it covers a
     * banner on a head or a pike in a hand, and it is a cull rather than a bound on what may be drawn - too large
     * keeps a mob nobody sees, too small clips a banner out of frame.
     */
    private static final double REACH = 2;

    private EntityCapture() {
    }

    /** One entity that survived the culling, with the distance that decided it, so nothing is measured twice. */
    private record Near(Entity entity, Location at, double distanceSquared) {
    }

    /**
     * @param ranges  how far this server sends each kind of entity, so a capture holds what the photographer sees
     * @param frustum the columns the frame can reach, or null to keep everything around the camera
     * @param shapes  what earlier captures already built for a mob, or null to build every one of them again
     * @param live    whether this is a viewfinder frame, which is what earns the reuse
     * @param most    models one capture may build, so a mob farm in frame cannot turn it into thousands. Nearest
     *                first, so what is dropped is what was furthest away
     * @param clip    the half-space the frame is confined to, or null for a view of everything around the eye.
     *                Dropped here rather than left to the tracer, because a mesh for something behind a mirror is a
     *                mesh built to be thrown away
     */
    static List<EntitySnapshot> take(Player viewer, Location eye, SkinCache skins, MobAssets assets, FramedMaps maps,
                                     EntityDetails details, TrackingRanges ranges, ChunkFrustum frustum,
                                     MobCache shapes, boolean live, int most, boolean includeSelf,
                                     CameraView.ClipPlane clip) {
        double search = ranges.widest();

        // Culled first and sorted second. A box query in a village comes back with everything, and sorting it all
        // put entities about to be thrown away through a comparator that allocated two Locations per comparison.
        List<Near> near = new ArrayList<>();
        for (Entity entity : viewer.getWorld().getNearbyEntities(eye, search, search, search)) {
            if (entity.equals(viewer) && !includeSelf) continue;
            // An ender dragon is nine entities and every one of its eight hitboxes answers "ender_dragon", so drawn
            // as they come they are nine dragons stacked. The client treats the parts as hitboxes, so this does too.
            if (entity instanceof ComplexEntityPart) continue;

            // Asked once and carried: wanted to cull by range, to cull by frame, and to order what is left.
            Location at = entity.getLocation();
            double away = distanceSquared(at, eye);

            // Out of range is one the photographer's own client was never sent. Out of frame is one the camera is
            // not pointed at, which is most of what a search returns since a camera sees a quarter of its
            // surroundings - and every one of those used to be built in full and thrown away by the trace.
            double range = ranges.forEntity(entity);
            if (away > range * range) continue;
            if (frustum != null && !frustum.mightSee(at.getX(), at.getY() + REACH, at.getZ(), REACH)) continue;
            // Behind the glass, so no part of it is in the reflection. Tested at head height rather than at the feet:
            // somebody standing on the far side of a wall has their feet nearer the plane than the rest of them.
            if (clip != null && !clip.keeps(at.getX(), at.getY() + REACH, at.getZ())) continue;

            near.add(new Near(entity, at, away));
        }
        near.sort(Comparator.comparingDouble(Near::distanceSquared));

        // Once for the whole capture, so every mob is judged for staleness against the same instant.
        long now = System.nanoTime();
        MobCache reusing = live ? shapes : null;
        if (reusing != null) {
            reusing.expire(now);
        }

        // Counting entities rather than the layers they came to, which is what {@code max-entities} says and what
        // bounds the work: one mob in armour is half a dozen snapshots and one part tree.
        int built = 0;
        List<EntitySnapshot> snapshots = new ArrayList<>();
        for (Near found : near) {
            if (built++ >= most) break;

            snapshots.addAll(drawn(found, skins, assets, maps, details, reusing, now));
        }

        return snapshots;
    }

    /**
     * What one entity is drawn as - built now, or an earlier capture's shape stood back up where it is now.
     *
     * <p>Only a living one is ever reused, and only its look: see {@link MobCache}. The rest bake something that
     * changes every tick - a dropped item's spin, a frame's map - so there is nothing in them worth holding.
     */
    private static List<EntitySnapshot> drawn(Near found, SkinCache skins, MobAssets assets, FramedMaps maps,
                                              EntityDetails details, MobCache shapes, long now) {
        Entity entity = found.entity();
        Location at = found.at();
        String type = entity.getType().name().toLowerCase(Locale.ROOT);
        boolean reusable = shapes != null && entity instanceof LivingEntity;

        if (reusable) {
            MobCache.Built held = shapes.get(entity.getUniqueId(),
                    shapes.allowedAgeNanos(Math.sqrt(found.distanceSquared())), now);
            if (held != null) {
                float[] pose = pose(entity, at, type);
                return held.standing(at.getX(), at.getY(), at.getZ(), pose[0], pose[1], pose[2]);
            }
        }

        MobCache.Built built = shapeOf(entity, at, type, skins, assets, details);
        if (built == null) return upended(entity, loose(entity, at, type, skins, assets, maps));

        if (reusable) {
            shapes.put(entity.getUniqueId(), built, now);
        }
        return built.all();
    }

    /**
     * Where this entity's body and head point, as body yaw, head yaw and pitch. One rule rather than two, since a
     * shape built at one pose and stood back up at another has to be handed the same three details both times.
     */
    private static float[] pose(Entity entity, Location at, String type) {
        float body = bodyYaw(entity);
        if (entity instanceof Player) return new float[]{body, at.getYaw(), at.getPitch()};

        return new float[]{body, headYaw(type, body, at.getYaw() + halfTurn(entity)), at.getPitch()};
    }

    /** Not {@link Location#distanceSquared}, which throws across worlds and is measured here on the same one. */
    private static double distanceSquared(Location at, Location eye) {
        double dx = at.getX() - eye.getX();
        double dy = at.getY() - eye.getY();
        double dz = at.getZ() - eye.getZ();

        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * A mob called Dinnerbone or Grumm, standing on its head.
     *
     * <p>Every layer of it rather than the body alone, which is what makes it a joke rather than a glitch: the client
     * turns the whole entity over in {@code setupRotations}, so its armor, its fleece and whatever it is holding go
     * with it.
     *
     * <p>The turn is a half circle about Z at half the height it is lifted by, which is the same thing the client's
     * lift and turn come to together - a rotation about a point is a rotation about the origin and a shift of twice
     * the distance to it.
     */
    private static List<EntitySnapshot> upended(Entity entity, List<EntitySnapshot> drawn) {
        if (drawn.isEmpty() || !MobNames.upsideDown(entity)) return drawn;

        float pivot = pivotOf(entity);
        List<EntitySnapshot> turned = new ArrayList<>(drawn.size());
        for (EntitySnapshot one : drawn) {
            turned.add(one.tilted(0, HALF_CIRCLE, pivot));
        }
        return List.copyOf(turned);
    }

    /** The same for a shape rather than a list, so the turn is held with the rest of what the mob looks like. */
    private static MobCache.Built upended(Entity entity, MobCache.Built built) {
        if (built == null || !MobNames.upsideDown(entity)) return built;

        return built.tilted(0, HALF_CIRCLE, pivotOf(entity));
    }

    private static float pivotOf(Entity entity) {
        return (float) (entity.getHeight() + UPENDED_CLEARANCE) * BLOCK / 2;
    }

    /** What the client lifts an upended mob by over its own height, so its feet clear the ground it stood on. */
    private static final double UPENDED_CLEARANCE = 0.1;

    private static final float BLOCK = 16;

    private static final float HALF_CIRCLE = (float) Math.PI;

    /**
     * The shape a player or a mob is drawn from, or null for anything that is neither - and for a type with no
     * authored mesh, or a player whose skin has not come down. Those fall through to {@link #loose}.
     */
    private static MobCache.Built shapeOf(Entity entity, Location at, String type, SkinCache skins,
                                          MobAssets assets, EntityDetails details) {
        if (entity instanceof Player player) return upended(entity, playerShape(player, at, type, skins, assets));
        if (entity instanceof Painting || entity instanceof ItemFrame || entity instanceof Item) return null;

        return upended(entity, mobShape(entity, at, type, assets, skins, details));
    }

    /**
     * Everything not drawn from a mob mesh: a painting, a thing in a frame, a dropped item, and the bounding box
     * that is the last resort for all of them. Never held, since each bakes in something that changes every tick.
     */
    private static List<EntitySnapshot> loose(Entity entity, Location at, String type, SkinCache skins,
                                              MobAssets assets, FramedMaps maps) {
        if (entity instanceof Painting hung) {
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
        }

        return boundingBox(entity, at, type, assets);
    }

    /** Null until the skin has come down, which takes a capture or two - better than somebody else's face. */
    private static MobCache.Built playerShape(Player player, Location at, String type, SkinCache skins, MobAssets assets) {
        String skin = skins.nameFor(player);
        if (skin == null) return null;

        float[] pose = pose(player, at, type);
        EntitySnapshot body = EntitySnapshot.player(at.getX(), at.getY(), at.getZ(), pose[0], pose[1], pose[2],
                skins.isSlim(player), skins.layersOf(player), skin, player.isSneaking());

        List<EntitySnapshot> worn = new ArrayList<>();
        List<EntitySnapshot> held = new ArrayList<>();
        worn.add(body);
        // No details: the two things they carry are an enderman's block and a golem's poppy, and a player is neither.
        MobEquipment.wornBy(player, body, type, assets, skins, null, worn, held);
        return new MobCache.Built(worn, held);
    }

    /** Null for a type with no authored shape, which is the caller's cue to fall back to a bounding box. */
    private static MobCache.Built mobShape(Entity entity, Location at, String type, MobAssets assets, SkinCache skins, EntityDetails details) {
        String variant = MobTextures.variantOf(entity, type);
        float[] pose = pose(entity, at, type);
        EntitySnapshot authored = EntitySnapshot.mob(
                type, variant,
                at.getX(), at.getY(), at.getZ(),
                pose[0], pose[1], pose[2],
                scaleOf(entity), isBaby(entity)
        );
        if (authored == null) return null;

        String skin = MobTextures.skinOf(entity, type, variant, authored.texture(), isBaby(entity), assets);
        Arms arms = armsOf(entity, at);
        EntitySnapshot posed = offering(entity, carrying(entity, hideUnworn(entity, authored.texture(skin))), details);
        EntitySnapshot bare = swimming(entity, posed, type, details);
        EntitySnapshot dressed = arms == null ? bare : arms.on(bare);

        List<EntitySnapshot> worn = new ArrayList<>();
        List<EntitySnapshot> held = new ArrayList<>();
        worn.add(dressed);
        worn.addAll(wornLayers(entity, dressed, type, variant));
        MobEquipment.wornBy(entity, dressed, type, assets, skins, details, worn, held);
        held.addAll(carried(entity, dressed, assets));
        return new MobCache.Built(worn, held);
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
        // The mushrooms on a bogged's head, which are built into its mesh and which BoggedModel hides once somebody
        // has sheared them off. Its mossy overlay stays: that is a layer of its own and the client keeps drawing it.
        if (entity instanceof Bogged bogged && bogged.isSheared()) {
            return mob.without("mushrooms");
        }

        return mob;
    }

    /**
     * The layers this mob's renderer draws over it, and empty for the great majority that wear none. Two of them come
     * and go with what the animal has been done to, and the rest simply are what the mob looks like.
     *
     * <p>A sheep is the special case: its fleece comes off one white texture that vanilla colors per animal, so the
     * dye travels with the layer, and a shorn sheep is a layer left out rather than a color. It is also the only one
     * shearing takes off - a bogged keeps its mossy overlay and loses only the mushrooms on its head, which are part
     * of its mesh.
     */
    private static List<EntitySnapshot> wornLayers(Entity entity, EntitySnapshot base, String type, String variant) {
        if (entity instanceof Sheep sheep) {
            // A sheep called jeb_ is between two dyes rather than wearing one, so its color is a number and not a
            // name - which is why this tints the fleece itself rather than handing a dye down.
            if (MobNames.named(sheep, MobNames.JEB) && !sheep.isSheared()) {
                return tinted(EntitySnapshot.over(base, type, variant), Tints.rainbow(sheep.getTicksLived()));
            }

            DyeColor dye = sheep.getColor();
            return EntitySnapshot.fleece(base, type, variant, sheep.isSheared(),
                    dye == null ? null : dye.name().toLowerCase(Locale.ROOT));
        }

        return EntitySnapshot.over(base, type, variant);
    }

    private static List<EntitySnapshot> tinted(List<EntitySnapshot> layers, int color) {
        List<EntitySnapshot> dyed = new ArrayList<>(layers.size());
        for (EntitySnapshot layer : layers) {
            dyed.add(layer.tint(color));
        }
        return List.copyOf(dyed);
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

    /** The client turns a squid about a point half a block up, which for a baby its own half scale takes care of. */
    private static final float SQUID_PIVOT = 8;

    /**
     * The mobs the client tilts bodily: a squid points along whatever it is jetting along, and a fish out of water
     * lies on its side.
     *
     * <p>A squid's two angles are read off the animal rather than worked out from its velocity, because the client
     * does not work them out either - it draws two fields the squid keeps for itself, easing each a tenth of the way
     * toward where it is going per tick. So one that has stopped is still pointing wherever it last swam, which is
     * something no amount of arithmetic on a velocity of nothing can recover.
     *
     * <p>Neither angle is turned round, and the spin goes inside the tip. The client states both in the space a mesh
     * is kept in - outside the flip it draws a model through, alongside the half block it turns a squid about - so
     * unlike a pose stated in the model's own axes, they come across as they are. Read the flip into the tip and a
     * squid comes out a half circle wrong: tentacles leading, mantle astern.
     */
    private static EntitySnapshot swimming(Entity entity, EntitySnapshot mob, String type, EntityDetails details) {
        float[] swim = details == null ? null : details.swimming(entity);
        if (swim != null) {
            return mob.swimming((float) Math.toRadians(swim[0]), (float) Math.toRadians(swim[1]), SQUID_PIVOT);
        }

        if (entity instanceof Fish && !entity.isInWater()) {
            return mob.tilted(0, (float) Math.toRadians(90), 0);
        }

        return mob;
    }

    /** An enderman poses its own arms out in front the moment it is holding something. Nothing else here does. */
    private static EntitySnapshot carrying(Entity entity, EntitySnapshot mob) {
        return entity instanceof Enderman enderman && enderman.getCarriedBlock() != null ? mob.carrying() : mob;
    }

    /**
     * A golem holds the arm the poppy is in out while it is offering, and the poppy is placed off that arm - so the
     * arm has to be posed before the flower is hung on it, not after.
     */
    private static EntitySnapshot offering(Entity entity, EntitySnapshot mob, EntityDetails details) {
        return details != null && entity instanceof IronGolem && details.offeringFlower(entity) ? mob.offering() : mob;
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
