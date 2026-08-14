package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One entity as it stood when the capture was taken, copied out of the server so the trace can run off-thread.
 *
 * @param bodyYaw where the body faces, in degrees, Bukkit's convention
 * @param headYaw where the head faces, which is a separate field on a living entity and usually differs
 * @param texture the texture name for {@link Textures}, so a skin and a pack's mob texture look the same here
 * @param tint    {@code 0xFFRRGGBB} to multiply every texel of this layer by, or 0 for the ordinary none. What a
 *                sheep's fleece needs: the assets hold one white wool texture and the color is the animal's own,
 *                so it cannot come from the texture name the way a cold cow's coat does
 */
public record EntitySnapshot(
        double x, double y, double z,
        float bodyYaw, float headYaw, float pitch,
        float scale,
        EntityModel model,
        String texture,
        int tint) {

    /** Untinted, which is every layer but a dyed one. */
    public EntitySnapshot(double x, double y, double z, float bodyYaw, float headYaw, float pitch, float scale, EntityModel model, String texture) {
        this(x, y, z, bodyYaw, headYaw, pitch, scale, model, texture, 0);
    }

    /**
     * What a capture scales an animal that is not yet grown by, and so how {@link #mob} recognizes one.
     *
     * <p>Reading it off the scale rather than being told is worth a word. Vanilla now builds a separate mesh for
     * most young animals - a calf is not a small cow, it is a big head on a short body - and picking the right one
     * needs to know which it is. Half is the one value a capture produces for a baby and for nothing else, so it
     * carries the fact without a second parameter on a public method a caller is already using. A caller that knows
     * outright should say so through {@link #mob(String, double, double, double, float, float, float, float,
     * boolean)}, and a capture that stops halving babies loses the baby mesh rather than drawing the wrong thing.
     */
    private static final float BABY_SCALE = 0.5f;

    /**
     * The same entity wearing a different texture.
     *
     * <p>For a variant: a cold cow is the same animal in a different coat, so the shape is reused and only the name
     * changes. Exists because the model type is not visible outside this package, so a caller cannot rebuild the
     * record itself.
     */
    public EntitySnapshot texture(String value) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model, value, tint);
    }

    /**
     * The same layer standing somewhere else, for a mob whose look an earlier capture built and whose position it
     * did not. The shape is the expensive part and the pose is six numbers - see {@code MobCache}.
     */
    public EntitySnapshot at(double x, double y, double z, float bodyYaw, float headYaw, float pitch) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model, texture, tint);
    }

    /** The same layer in a dye color, for the one mob whose color is not in its texture. */
    public EntitySnapshot tint(int value) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model, texture, value);
    }

    /**
     * The same mob with some of its parts left out, named as vanilla names them.
     *
     * <p>For the parts a mesh always carries and the client only sometimes draws. A donkey's mesh has its two
     * panniers built in, hidden unless the animal is really carrying a chest - so drawn as it comes, every donkey in
     * the world has been to the shops. A name that is not in the mesh is ignored, so a caller may ask for the same
     * pair on every chested animal without knowing which of them build them.
     */
    public EntitySnapshot without(String... parts) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.without(Set.of(parts)), texture, tint);
    }

    /** A player, whose head turns, whose arms may be slim, and who chooses which skin layers to wear. */
    public static EntitySnapshot player(double x, double y, double z, float bodyYaw, float headYaw, float pitch, boolean slim, SkinLayers layers, String texture) {
        return player(x, y, z, bodyYaw, headYaw, pitch, slim, layers, texture, false);
    }

    /**
     * @param crouching whether they are sneaking, which tips the torso forward and drops the head under it rather
     *                  than merely lowering the whole player
     */
    public static EntitySnapshot player(double x, double y, double z, float bodyYaw, float headYaw, float pitch,
                                        boolean slim, SkinLayers layers, String texture, boolean crouching) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, 1f, EntityModel.player(slim, layers, crouching), texture);
    }

    /**
     * A mob with a mesh, texture and all - or null when there is none for this type, which is the caller's cue to
     * fall back to {@link #box}.
     *
     * <p>The type is a vanilla entity id, lowercase and unqualified: {@code creeper}, {@code zombie_villager}.
     *
     * @param scale multiplies the whole model, for the handful of mobs that come in sizes. A slime's mesh is one
     *              size and its size is a property, which is how the game does it too
     */
    public static EntitySnapshot mob(String type, double x, double y, double z, float bodyYaw, float headYaw, float pitch, float scale) {
        return mob(type, x, y, z, bodyYaw, headYaw, pitch, scale, scale == BABY_SCALE);
    }

    /**
     * The same, saying outright whether this one is grown.
     *
     * @param baby draws it from the young mesh where vanilla has one, at that mesh's own size rather than at
     *             {@code scale} - a calf mesh is already calf sized, so halving it again would produce a kitten
     */
    public static EntitySnapshot mob(String type, double x, double y, double z, float bodyYaw, float headYaw, float pitch, float scale, boolean baby) {
        return mob(type, null, x, y, z, bodyYaw, headYaw, pitch, scale, baby);
    }

    /**
     * And the same again for an animal whose coat is a shape rather than a color.
     *
     * @param variant the word the assets name the coat by - {@code cold}, {@code warm}, {@code brown} - or null for
     *                a type with no variants. Cold and warm cows, pigs and chickens are built from meshes of their
     *                own, and drawing one from the temperate mesh puts its horns where the texture has none
     */
    public static EntitySnapshot mob(String type, String variant, double x, double y, double z, float bodyYaw, float headYaw, float pitch, float scale, boolean baby) {
        EntityMeshes.Mob mob = EntityMeshes.of(type, variant, baby);
        if (mob == null) return null;

        float drawn = (baby && EntityMeshes.hasBaby(type) ? 1f : scale) * mob.scale();
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, drawn, mob.model(), mob.texture());
    }

    /**
     * The layers a mob wears over its skin, sitting on {@code base}, and empty for the many that wear none.
     *
     * <p>Each is its own snapshot rather than more cubes on the base model, because a layer comes from a texture of
     * its own and a snapshot samples one texture. That costs nothing: the renderer keeps the nearest entity texel
     * along the ray whatever entity it belongs to, and a layer's cubes are inflated outward, so a stray's frost lands
     * in front of the bones it covers without anything having to order the two.
     */
    public static List<EntitySnapshot> over(EntitySnapshot base, String type) {
        return over(base, type, null);
    }

    /** The same for an animal that has variants, so the layers are looked up off the mesh the base was built from. */
    public static List<EntitySnapshot> over(EntitySnapshot base, String type, String variant) {
        // Which of the two meshes this snapshot was built from, since a lamb's fleece is not a sheep's shrunk and
        // vanilla only gives the layer to the grown one.
        EntityMeshes.Mob young = EntityMeshes.of(type, variant, true);
        EntityMeshes.Mob mob = young != null && young.model() == base.model() ? young : EntityMeshes.of(type, variant, false);
        if (mob == null) return List.of();

        List<EntitySnapshot> worn = new ArrayList<>(mob.over().size());
        for (EntityMeshes.Mob layer : mob.over()) {
            worn.add(new EntitySnapshot(base.x(), base.y(), base.z(), base.bodyYaw(), base.headYaw(), base.pitch(),
                    base.scale(), layer.model(), layer.texture()));
        }
        return List.copyOf(worn);
    }

    /**
     * A sheep's fleece: the one white wool texture in the assets, multiplied by the animal's own dye - or nothing
     * once it is shorn, because a shorn sheep wears no fleece rather than a colorless one.
     *
     * <p>Its own factory because the fleece is the only worn layer whose color is not in its texture. There are
     * sixteen wools and one wool texture, so the dye has to travel with the layer, and a fleece drawn from the
     * texture alone is white on every sheep in the world.
     *
     * @param dye the lowercase dye name, {@code light_blue} and the rest, or null for a sheep with none
     */
    public static List<EntitySnapshot> fleece(EntitySnapshot base, String type, String variant, boolean sheared, String dye) {
        if (sheared) return List.of();

        List<EntitySnapshot> worn = over(base, type, variant);
        if (dye == null) return worn;

        List<EntitySnapshot> dyed = new ArrayList<>(worn.size());
        for (EntitySnapshot layer : worn) {
            dyed.add(layer.tint(Tints.wool(dye)));
        }
        return List.copyOf(dyed);
    }

    /**
     * A piece of equipment on a mob: its own mesh and its own texture, in the same place and the same pose.
     *
     * <p>Its own snapshot for the same reason a fleece is - a layer samples one texture, and a chestplate does not
     * share the texture of the body under it. Nothing has to order them either: every equipment mesh is the body
     * inflated by the amount the client inflates it, so it lands in front, and the renderer keeps the nearest entity
     * texel along the ray whichever layer it came from.
     *
     * <p>Null when this version would not give the mesh up, which is the caller cue to draw the mob without it rather
     * than to fail.
     *
     * @param mesh a name from the equipment table - {@code armor/chest}, {@code pig_saddle}
     */
    public static EntitySnapshot worn(EntitySnapshot base, String mesh, String texture) {
        return worn(base, mesh, texture, false);
    }

    /**
     * @param crouching whether the wearer is sneaking, which moves a piece as well as turning it - the rotations
     *                  below come across on their own, and a helmet that only turned would float where the head
     *                  would have been standing
     */
    public static EntitySnapshot worn(EntitySnapshot base, String mesh, String texture, boolean crouching) {
        EntityModel model = EntityMeshes.worn(mesh);
        if (model == null) return null;

        // Standing the way the mob under it stands, which the piece's own mesh cannot know: one armor mesh is worn by
        // every humanoid and is posed as a plain one. The crouch goes on first, since posing over it then agrees on
        // the rotations rather than adding a second lean to them.
        EntityModel worn = crouching ? model.crouched() : model;
        return new EntitySnapshot(base.x(), base.y(), base.z(), base.bodyYaw(), base.headYaw(), base.pitch(),
                base.scale(), worn.posedLike(base.model()), texture);
    }

    /** The parts vanilla puts an item in, and the only two names it looks them up by. */
    private static final String RIGHT_ARM = "right_arm";

    private static final String LEFT_ARM = "left_arm";

    /**
     * An item in one of a mob's hands, or null when there is nowhere to put it.
     *
     * <p>Placed and turned off the holder's own mesh rather than from a table: the arm is looked up by name, and the
     * item inherits its rotation, so an archer's levelled arm carries its bow up with it. A mob with no arm to find
     * holds nothing, which is also true of a cow. How the item itself is turned is {@link ItemPoses}.
     *
     * <p>The item rides on the holder's position and yaw rather than being placed in the world, which is what keeps
     * it in the hand - one rotation rather than two that have to agree.
     *
     * @param rightArm which arm it is in, since a left-handed mob holds its main-hand item in its left one and the
     *                  client poses an item by the arm rather than by the hand
     * @param sprite   one layer from {@link ItemModels#held}, which decided already whether the item is a flat icon or
     *                 a block model and which texture this layer of it wears - this only puts it in the hand
     */
    public static EntitySnapshot held(EntitySnapshot holder, boolean rightArm, EntitySnapshot sprite, ItemPoses.Pose pose) {
        if (sprite == null || pose == null) return null;

        EntityModel.Joint arm = holder.model().joint(rightArm ? RIGHT_ARM : LEFT_ARM);
        if (arm == null) return null;

        // Head yaw and pitch are the body's: an item is not a head part, so nothing here turns with the head, and
        // saying so plainly beats carrying values that have no effect.
        return new EntitySnapshot(holder.x(), holder.y(), holder.z(), holder.bodyYaw(), holder.bodyYaw(), 0,
                holder.scale(), sprite.model().onJoint(arm, pose, false), sprite.texture(), sprite.tint());
    }

    /** The part vanilla hangs a head layer off, and the only name it looks one up by. */
    private static final String HEAD = "head";

    /**
     * A block worn on a mob's head, which is how a snow golem carries its pumpkin.
     *
     * <p>Not equipment and not a held item: vanilla draws it as a further pass over the head part, so it turns with
     * the head rather than with the body - which is why this keeps the wearer's head yaw and pitch where
     * {@link #held} drops them.
     *
     * @param block one layer from {@link ItemModels#held}, already resolved to a shape and a texture
     */
    public static EntitySnapshot onHead(EntitySnapshot wearer, EntitySnapshot block, ItemPoses.Pose pose) {
        return on(wearer, HEAD, block, pose, true);
    }

    /**
     * A block carried on some other part, which is how a sulfur cube shows what has been put inside it.
     *
     * @param head whether it turns with the wearer's head, which a pumpkin does and a block in a body does not
     */
    public static EntitySnapshot on(EntitySnapshot wearer, String part, EntitySnapshot block,
                                    ItemPoses.Pose pose, boolean head) {
        if (block == null || pose == null) return null;

        EntityModel.Joint joint = wearer.model().joint(part);
        if (joint == null) return null;

        // Both head angles run backwards on a block, and only on a block: a mob's mesh is built flipped and turned
        // back the right way, a block model is built the right way round to begin with, so a turn stated in the
        // first space goes the other way in the second. Left and right swap, up and down swap, and forward stays
        // forward - which is exactly what the two mirrored axes do and nothing a single wrong sign would.
        return new EntitySnapshot(wearer.x(), wearer.y(), wearer.z(), wearer.bodyYaw(), wearer.headYaw(),
                wearer.pitch(), wearer.scale(), block.model().onJoint(joint, pose, head), block.texture(), block.tint());
    }

    /**
     * The block a minecart is carrying, riding in it - which is what tells a tnt minecart from a plain one, since the
     * cart itself is the same shape and the same texture whichever kind it is.
     *
     * <p>On the cart's own position and yaw rather than placed in the world, for the reason {@link #held} is: one
     * rotation rather than two that have to agree.
     *
     * @param block  one layer from {@link ItemModels#held}, already resolved to a shape and a texture
     * @param offset how far up the cart states its block sits, in entity pixels
     */
    public static EntitySnapshot inCart(EntitySnapshot cart, EntitySnapshot block, int offset) {
        if (block == null) return null;

        return new EntitySnapshot(cart.x(), cart.y(), cart.z(), cart.bodyYaw(), cart.bodyYaw(), 0,
                cart.scale(), block.model().inCart(offset), block.texture(), block.tint());
    }

    /** The same mob with its arms out in front, which its own model does the moment it picks a block up. */
    public EntitySnapshot carrying() {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.carrying(), texture, tint);
    }

    /** The same mob with one arm out, which is how a golem stands while it has a poppy to hand over. */
    public EntitySnapshot offering() {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.offering(), texture, tint);
    }

    /**
     * The same mob tilted bodily, the way the client tilts the few that swim at an angle.
     *
     * @param pivotY where the tilt turns about, in entity pixels off the feet
     */
    public EntitySnapshot tilted(float xRot, float zRot, float pivotY) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.tilted(xRot, zRot, pivotY), texture, tint);
    }

    /**
     * The same mob pointing where a squid is swimming: tipped over about X and then spun about its own axis.
     *
     * <p>Not two of {@link #tilted}'s three angles, because the spin belongs inside the tip rather than beside it -
     * which is where the client applies it, and the two do not commute.
     *
     * @param pivotY where the turn happens, in entity pixels off the feet
     */
    public EntitySnapshot swimming(float tip, float spin, float pivotY) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.swimming(tip, spin, pivotY), texture, tint);
    }

    /**
     * The same turned bodily about all three axes, for a block entity that sits on whichever of its block's six faces
     * it was placed against.
     *
     * @param pivotY in entity pixels off the block's floor, which for these is its middle
     */
    public EntitySnapshot turned(float xRot, float yRot, float zRot, float pivotY) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale,
                model.tilted(xRot, yRot, zRot, pivotY), texture, tint);
    }

    /**
     * The same mob with one part turned as the client's own animation turns it.
     *
     * <p>For the poses that are how a mob stands rather than motion - a zombie's arms out in front, an archer's arms
     * levelled. A mesh carries the rest pose only, so without this they stand there like scarecrows.
     */
    public EntitySnapshot posed(String part, float xRot, float yRot, float zRot) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, pitch, scale, model.turned(part, xRot, yRot, zRot), texture, tint);
    }

    /**
     * A painting, as the two layers it takes: the picture, and the planks behind and around it.
     *
     * <p>Both are the same slab at the same place and differ only in which sides of it draw, so nothing has to order
     * them - a ray reaching the front never reaches the back, and one reaching the rim never reaches the picture.
     *
     * @param facing     where the picture points, in the yaw convention the rest of this takes
     * @param blocksWide the variant's own size, which is what makes one painting four times another
     */
    public static List<EntitySnapshot> painting(double x, double y, double z, float facing,
                                                int blocksWide, int blocksHigh, String art, String back) {
        return List.of(
                new EntitySnapshot(x, y, z, facing, facing, 0, 1f, EntityModel.painting(blocksWide, blocksHigh, true), art),
                new EntitySnapshot(x, y, z, facing, facing, 0, 1f, EntityModel.painting(blocksWide, blocksHigh, false), back)
        );
    }

    /**
     * One layer of an item frame's own frame, centred on the block's middle the way its renderer centres it.
     *
     * @param layer one layer from {@link ItemModels#modelled}
     */
    public static EntitySnapshot frame(double x, double y, double z, float facing, EntitySnapshot layer) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                layer.model().centredInBox(), layer.texture(), layer.tint());
    }

    /**
     * And one layer of what is in it, at the place and size the client hangs it.
     *
     * @param layer one layer from {@link ItemModels#held}, already resolved to a shape and a texture
     * @param spin  how far round the frame has been turned, in radians
     */
    public static EntitySnapshot framed(double x, double y, double z, float facing,
                                        EntitySnapshot layer, ItemPoses.Pose fixed, float spin) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                layer.model().inFrame(fixed, spin), layer.texture(), layer.tint());
    }

    /**
     * One layer of an item standing on a shelf, in whichever of the three slots it is in.
     *
     * @param layer  one layer from {@link ItemModels#held}, already resolved to a shape and a texture
     * @param offset where in the block the slot is, in entity pixels, in the shelf's own turned frame
     */
    public static EntitySnapshot onShelf(double x, double y, double z, float facing,
                                         EntitySnapshot layer, ItemPoses.Pose shelf, float[] offset) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                layer.model().onShelf(shelf, offset), layer.texture(), layer.tint());
    }

    /**
     * The map hanging in an item frame, which fills the frame rather than sitting in it as an item would.
     *
     * <p>A block across and a hundredth of a block proud of where an item would be, at whichever quarter turn the
     * frame was clicked round to - a map has four orientations where an item has eight, which is vanilla's own
     * {@code rotation % 4}.
     *
     * @param texture the map's own pixels, published into the atlas by the caller
     * @param spin    in radians, in the client's own sign
     */
    public static EntitySnapshot framedMap(double x, double y, double z, float facing, String texture, float spin) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                EntityModel.picture(MAP_HALF_WIDTH, MAP_HALF_WIDTH, -MAP_FRONT, -spin), texture);
    }

    /**
     * A flat picture standing at a point and facing a way, which is what a sign's lettering is.
     *
     * <p>Placed rather than hung: the caller has already worked out where the text plane is, so this is the picture
     * and nothing else. The two half sizes are what let a sign's strip of writing be wider than it is tall.
     *
     * @param facing where it points, in the yaw convention a block model takes
     */
    public static EntitySnapshot lettering(double x, double y, double z, float facing,
                                           float halfWidth, float halfHeight, String texture) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                EntityModel.picture(halfWidth, halfHeight, 0, 0), texture);
    }

    /**
     * A map of a MapGUI wall, drawn on the face of the block it hangs on.
     *
     * <p>A wall puts nothing in the world - its maps and the frames holding them are sent to a viewer's client and
     * nowhere else - so this is placed from the wall's own layout rather than found by looking at the blocks. Which
     * is why it takes the block being hung on and pushes the picture out to its face itself, where a real frame is
     * an entity already standing in the space in front.
     *
     * @param x       the middle of the block the wall map hangs on, and {@code y} and {@code z} with it
     * @param facing  where the picture points, in the yaw convention a block model takes
     * @param texture the wall's own pixels, published into the atlas by the caller
     */
    public static EntitySnapshot wallMap(double x, double y, double z, float facing, String texture) {
        return new EntitySnapshot(x, y, z, facing, facing, 0, 1f,
                EntityModel.picture(MAP_HALF_WIDTH, MAP_HALF_WIDTH, WALL_FRONT, 0), texture);
    }

    /**
     * Where a wall's map sits, in entity pixels off the middle of its block: on the block's own face, and a hair
     * proud of it so that it does not fight whatever the block is made of.
     */
    private static final float WALL_FRONT = 8 + 16 / 128f;

    /** Half a block, since a framed map is drawn the full width of the block the frame is in. */
    private static final float MAP_HALF_WIDTH = 8;

    /**
     * Where a map sits, in entity pixels: the front of the frame's backplate, less the hundredth of a block vanilla
     * lifts it by so that it does not fight the plate. Negative because a frame's own model arrives a half circle
     * about Y from where its json states it - see {@link Turns#halfTurned}.
     */
    private static final float MAP_FRONT = 0.4375f * 16 - 16 / 128f;

    /**
     * The same tipped onto its back or its front, for a frame on a floor or a ceiling.
     *
     * <p>Vanilla turns those about X <i>outside</i> its half circle about Y, and the trace's own turn is outside
     * everything - so the angle stated here is the one that lands the same way round after being carried through
     * that half circle, which reverses it.
     *
     * @param xRot in radians
     */
    public EntitySnapshot tipped(float xRot) {
        return xRot == 0 ? this : turned(xRot, 0, 0, 0);
    }

    /** Anything with no mesh, drawn as its own bounding box. */
    public static EntitySnapshot box(double x, double y, double z, float bodyYaw, float headYaw, double width, double height, String texture) {
        return new EntitySnapshot(x, y, z, bodyYaw, headYaw, 0, 1f, EntityModel.box(width, height), texture);
    }

    /** How far from the feet position any part of it can reach, for the bounding sphere the search uses. */
    double reach() {
        return Math.max(model.radius(), model.height() / 16.0) * scale;
    }
}
