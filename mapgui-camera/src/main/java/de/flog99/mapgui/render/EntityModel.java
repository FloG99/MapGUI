package de.flog99.mapgui.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The posed parts an entity is drawn from, and how far they reach.
 *
 * <p>Nearly all of these are extracted from the client rather than written here - see {@link MeshExtractor}. What is
 * left is the handful with no vanilla mesh: the player, whose overlay layers are per-player, and the stand-ins.
 *
 * @param height in entity pixels, for the screen rect that bounds the search
 * @param radius widest horizontal reach in blocks, likewise
 * @param culled whether only the near side of a cube may draw. False for a mob, since vanilla draws entity models
 *               with culling off and several rely on it - a chicken's leg is textured on one face and its underside,
 *               so culled it is a hole with the far side showing through. True for the things vanilla draws with a
 *               culling render type, where a flat quad carries the same picture mirrored on its back
 */
record EntityModel(List<MeshPart> parts, float height, float radius, boolean culled) {

    private static final float PIXEL = 1 / 16f;

    /** Vanilla's inflation for the hat, and for the other six overlay parts. */
    private static final float HAT_GROW = 0.5f;
    private static final float OVERLAY_GROW = 0.25f;

    /** The vanilla item quad is a pixel thick, and a ground item is drawn at half scale. */
    private static final float SPRITE_THICKNESS = 0.5f;

    /** The box every model is authored in, item and block alike, in the sixteenths it states its own coordinates in. */
    private static final float MODEL_BOX = 16;

    /**
     * And the middle of it, which is where a held thing is turned about - the client's own convention rather than a
     * choice here. The difference shows on anything that does not fill its box: a slab centred on its own geometry
     * sits half a block too high.
     */
    private static final float MODEL_MIDDLE = MODEL_BOX / 2;

    /** What the {@code ground} transform scales an item and a block to: half the model box, and a quarter of it. */
    private static final float DROPPED_SPRITE = 8;

    private static final float DROPPED_BLOCK = 4;

    /** Where a player's head turns from: the top of the torso, with nothing in front of it. */
    private static final float PLAYER_NECK = 24;

    /** How far out to the side a shoulder is, and how far the arm hangs below it. Vanilla's own two numbers. */
    private static final float ARM_PIVOT = 5;

    private static final float ARM_HANG = 10;

    /**
     * The same model with some of its parts left out, and their children with them - for the parts vanilla draws only
     * sometimes, like the panniers a donkey's mesh always carries. Re-measured rather than keeping the original
     * extent, since dropping a part can shrink the model and a bound that no longer fits searches the wrong pixels.
     */
    EntityModel without(Set<String> names) {
        if (names.isEmpty()) return this;

        List<MeshPart> kept = prune(parts, names);
        return kept.size() == parts.size() && kept.equals(parts) ? this : of(kept, culled);
    }

    private static List<MeshPart> prune(List<MeshPart> parts, Set<String> names) {
        List<MeshPart> kept = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            if (names.contains(part.name())) {
                continue;
            }

            kept.add(part.withChildren(prune(part.children(), names)));
        }
        return kept;
    }

    /**
     * Where a named part sits and how it is turned, in this model's own space.
     *
     * @param turn 3x3, so that a caller can put something else in the same frame - what an item in a hand needs
     */
    record Joint(float x, float y, float z, float[] turn) {
    }

    /**
     * The joint a named part turns about, or null when this model has no such part. Accumulated down the tree,
     * because a part is stated against its parent and in its parent's turned frame - so an arm on a body leaning
     * forward is further forward than its own numbers say.
     */
    Joint joint(String name) {
        return joint(parts, name, 0, 0, 0, Turns.none());
    }

    private static Joint joint(List<MeshPart> parts, String name, float x, float y, float z, float[] turn) {
        for (MeshPart part : parts) {
            float[] offset = Turns.apply(turn, part.x(), part.y(), part.z());
            float atX = x + offset[0];
            float atY = y + offset[1];
            float atZ = z + offset[2];
            float[] turned = Turns.times(turn, Turns.part(part.xRot(), part.yRot(), part.zRot()));

            if (part.name().equals(name)) return new Joint(atX, atY, atZ, turned);

            Joint found = joint(part.children(), name, atX, atY, atZ, turned);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The same model standing the way another one stands, part by matching name - what armor needs, since one armor
     * mesh is worn by everything humanoid and carries a plain humanoid's arms-at-its-sides pose. Worn by a zombie
     * whose arms are out in front, the chestplate would hang where the body is not.
     *
     * <p>Names rather than positions, because vanilla builds armor from the humanoid body and gives the parts the
     * same names. A part the other model does not have keeps its own pose, which is how a saddle comes through.
     *
     * <p>Rotations only. Where a part sits cannot be carried across, because the two models need not hang their
     * parts off the same parents: an absolute height copied onto a part measured from its own parent's is added to
     * that parent's again, which put a player's leg armor on his head.
     */
    EntityModel posedLike(EntityModel other) {
        Map<String, float[]> poses = new HashMap<>();
        collectPoses(other.parts, poses);
        if (poses.isEmpty()) return this;

        List<MeshPart> matched = matchPoses(parts, poses);
        return matched.equals(parts) ? this : of(matched, culled);
    }

    private static void collectPoses(List<MeshPart> parts, Map<String, float[]> into) {
        for (MeshPart part : parts) {
            into.put(part.name(), new float[]{part.xRot(), part.yRot(), part.zRot()});
            collectPoses(part.children(), into);
        }
    }

    private static List<MeshPart> matchPoses(List<MeshPart> parts, Map<String, float[]> poses) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            float[] pose = poses.get(part.name());
            MeshPart posed = pose == null ? part : part.withRotation(pose[0], pose[1], pose[2]);
            out.add(posed.withChildren(matchPoses(part.children(), poses)));
        }
        return List.copyOf(out);
    }

    /**
     * The same model with one part turned to a stated rotation, or unchanged when it has no such part - for the poses
     * the client applies over a rest mesh, like an archer levelling both arms at what it is shooting at.
     */
    EntityModel turned(String name, float xRot, float yRot, float zRot) {
        List<MeshPart> posed = turn(parts, name, xRot, yRot, zRot);
        return posed.equals(parts) ? this : of(posed, culled);
    }

    private static List<MeshPart> turn(List<MeshPart> parts, String name, float xRot, float yRot, float zRot) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            out.add(part.name().equals(name)
                    ? part.withRotation(xRot, yRot, zRot)
                    : part.withChildren(turn(part.children(), name, xRot, yRot, zRot)));
        }
        return List.copyOf(out);
    }

    /**
     * The same model tilted bodily, for the mobs whose whole body turns rather than their head - a squid swims at
     * whatever angle it is jetting along, a fish out of water lies on its side.
     *
     * <p>The pivot is the caller's to state: the client turns a squid about a point half a block up, and turning a
     * body about its feet swings it out sideways instead of tumbling in place.
     *
     * @param pivotY in entity pixels off the feet
     */
    EntityModel tilted(float xRot, float zRot, float pivotY) {
        if (xRot == 0 && zRot == 0) return this;

        return of(List.of(new MeshPart("tilt", false, 0, pivotY, 0, xRot, 0, zRot, 1, 1, 1,
                List.of(), moved(0, -pivotY, 0))), culled);
    }

    /**
     * A model from its parts, measuring the extent of the tree rather than taking it on trust - so an extracted mesh
     * and an authored one bound themselves the same way, and neither states a height that could drift from its own
     * geometry.
     */
    static EntityModel of(List<MeshPart> parts) {
        return of(parts, false);
    }

    /** The same, for the handful of models whose vanilla render type culls back faces. */
    static EntityModel of(List<MeshPart> parts, boolean culled) {
        float[] bounds = {Float.MAX_VALUE, -Float.MAX_VALUE, 0};
        for (MeshPart part : parts) {
            measure(part, 0, 0, 0, 1, bounds);
        }
        float height = bounds[1] == -Float.MAX_VALUE ? 0 : bounds[1];

        // A sphere about the centre the search projects. The 1.45 is because a corner reaches further than an edge
        // when turned about the vertical axis, and the vertical term starts at the lowest cube rather than the feet,
        // since plenty of models reach below them - a ghast's tentacles hang well under where it stands.
        float across = bounds[2] * 1.45f;
        float middle = height / 2;
        float down = Math.max(Math.abs(height - middle), Math.abs((bounds[0] == Float.MAX_VALUE ? 0 : bounds[0]) - middle));
        return new EntityModel(List.copyOf(parts), height, (float) Math.hypot(across, down) * PIXEL, culled);
    }

    /**
     * Classic 4-pixel arms, or the 3-pixel slim ones - the profile says which - and only the overlay parts the
     * player has switched on.
     *
     * <p>Authored rather than extracted because the layers are a per-player choice: vanilla's player mesh carries
     * all six of them as parts it hides at render time, and hiding is not something a baked mesh remembers.
     */
    static EntityModel player(boolean slim, SkinLayers layers, boolean crouching) {
        float armWidth = slim ? 3 : 4;

        List<MeshCube> head = new ArrayList<>();
        head.add(MeshCube.box(-4, 0, -4, 8, 8, 8, 0, 0, 64, 64, 0));
        if (layers.hat()) {
            head.add(MeshCube.box(-4, 0, -4, 8, 8, 8, 32, 0, 64, 64, HAT_GROW));
        }

        List<MeshCube> body = new ArrayList<>();
        body.add(MeshCube.box(-4, -12, -2, 8, 12, 4, 16, 16, 64, 64, 0));
        if (layers.jacket()) {
            body.add(MeshCube.box(-4, -12, -2, 8, 12, 4, 16, 32, 64, 64, OVERLAY_GROW));
        }

        // Legs, with the right leg's patch on the player's right, which is +X. Reading it the other way round dresses
        // a player in their own mirror image.
        EntityModel standing = of(List.of(
                MeshPart.at("body", 0, PLAYER_NECK, 0, List.copyOf(body), List.of()),
                MeshPart.at("head", 0, PLAYER_NECK, 0, List.copyOf(head), List.of()),
                leg("right_leg", LEG_PIVOT, 0, 16, 0, 32, layers.rightPants()),
                leg("left_leg", -LEG_PIVOT, 16, 48, 0, 48, layers.leftPants()),
                arm("right_arm", ARM_PIVOT, armWidth, -1, 40, 16, 40, 32, layers.rightSleeve()),
                arm("left_arm", -ARM_PIVOT, armWidth, 1 - armWidth, 32, 48, 48, 48, layers.leftSleeve())
        ));

        return crouching ? standing.crouched() : standing;
    }

    /**
     * This model sneaking, in the client's own numbers off {@code HumanoidModel}: the torso tips over its own neck,
     * the head drops under it, and the legs slide back to stay beneath.
     *
     * <p>Shifts rather than places, and by part name, which is what lets one method pose both a player and the armor
     * worn over him. The two do not hang their parts off the same parents - the player's are authored flat and the
     * armor's are extracted under a root - so a height means different places in each while a shift means the same.
     *
     * <p>The numbers are turned round into this frame, which is the one extracted meshes come out in: a mob's model
     * hangs downward off its neck and is drawn flipped, so vanilla's <i>plus</i> y is <i>down</i> here and an x
     * rotation changes sign with it. Z does not, since the flip leaves it alone.
     */
    EntityModel crouched() {
        return of(crouch(parts), culled);
    }

    private static List<MeshPart> crouch(List<MeshPart> parts) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            MeshPart posed = switch (part.name()) {
                case "head" -> part.moved(0, -CROUCH_HEAD_DROP, 0);
                case "body" -> part.moved(0, -CROUCH_DROP, 0).withRotation(-CROUCH_LEAN, part.yRot(), part.zRot());
                case "right_arm", "left_arm" -> part.moved(0, -CROUCH_DROP, 0)
                        .withRotation(part.xRot() - CROUCH_ARM_LEAN, part.yRot(), part.zRot());
                case "right_leg", "left_leg" -> part.moved(0, 0, CROUCH_LEG_BACK);
                default -> part;
            };
            out.add(posed.withChildren(crouch(posed.children())));
        }
        return List.copyOf(out);
    }

    /** How far the torso and the arms drop, in the pixels a mesh is measured in. The head goes further. */
    private static final float CROUCH_DROP = 3.2f;

    private static final float CROUCH_HEAD_DROP = 4.2f;

    /** Radians, since a part's rotations are. Half of one is a good way over. */
    private static final float CROUCH_LEAN = 0.5f;

    private static final float CROUCH_ARM_LEAN = 0.4f;

    /** The legs go back rather than down, which is what keeps the feet under a torso that has tipped forward. */
    private static final float CROUCH_LEG_BACK = 4;

    /** Vanilla's own {@code ±1.9} rounded to the middle of the leg, which is where this model's boxes already sit. */
    private static final float LEG_PIVOT = 2;

    /** The hip, half way up a player, which a leg hangs from and is measured down from. */
    private static final float LEG_TOP = 12;

    /**
     * One leg, as a part of its own rather than cubes in the torso, because crouching moves the legs and the torso
     * differently - the torso tips forward and the legs stay upright and slide back under it.
     */
    private static MeshPart leg(String name, float x, int u, int v, int overlayU, int overlayV, boolean pants) {
        List<MeshCube> cubes = new ArrayList<>();
        cubes.add(MeshCube.box(-2, -LEG_TOP, -2, 4, 12, 4, u, v, 64, 64, 0));
        if (pants) {
            cubes.add(MeshCube.box(-2, -LEG_TOP, -2, 4, 12, 4, overlayU, overlayV, 64, 64, OVERLAY_GROW));
        }

        return MeshPart.at(name, x, LEG_TOP, 0, List.copyOf(cubes), List.of());
    }

    /**
     * One arm, as a part of its own rather than two cubes in the torso, because an item is placed off the arm holding
     * it and there is nothing to place it off unless the arm has a pivot. The pivot is the shoulder, at vanilla's own
     * {@code (±5, 2)} from the top of the torso.
     */
    private static MeshPart arm(String name, float x, float width, float from,
                               int u, int v, int overlayU, int overlayV, boolean sleeve) {

        List<MeshCube> cubes = new ArrayList<>();
        cubes.add(MeshCube.box(from, -ARM_HANG, -2, width, 12, 4, u, v, 64, 64, 0));
        if (sleeve) {
            cubes.add(MeshCube.box(from, -ARM_HANG, -2, width, 12, 4, overlayU, overlayV, 64, 64, OVERLAY_GROW));
        }

        return MeshPart.at(name, x, PLAYER_NECK - 2, 0, List.copyOf(cubes), List.of());
    }

    /**
     * One box the size of the entity's own bounding box, for anything with no mesh.
     *
     * <p>Correctly sized and correctly turned, and at map resolution a mob more than a few blocks off is a
     * handful of pixels anyway - so this is much closer to right than it sounds.
     */
    static EntityModel box(double width, double height) {
        float half = (float) (width / 2 * 16);
        float tall = (float) (height * 16);
        return of(List.of(MeshPart.of("body", List.of(MeshCube.plain(-half, 0, -half, half * 2, tall, half * 2)))), true);
    }

    /**
     * A dropped item: the item's sprite as one flat quad, half a block across, resting on the ground - the size the
     * client's {@code ground} transform states. The bob is left out, since a capture is one instant and its phase is
     * not something the server hands over, so the item is drawn where it averages out.
     *
     * <p>Which way the quad faces is the caller's business: its front is local -Z like every other model here.
     */
    static EntityModel itemSprite() {
        return of(List.of(MeshPart.of("item", List.of(MeshCube.sprite(
                -DROPPED_SPRITE / 2, 0, -SPRITE_THICKNESS / 2, DROPPED_SPRITE, DROPPED_SPRITE, SPRITE_THICKNESS)))), true);
    }

    /**
     * The same picture at the size and place the item model states, extruded along its own outline, for an item in a
     * hand. Full size rather than the dropped one, since a held item is scaled by its {@code thirdperson} transform
     * and starting from the halved shape would apply both.
     *
     * <p>Built from the icon rather than from its frame - see {@link SpriteShape} - because a held item is seen edge
     * on often enough that where its one pixel of thickness sits is something anybody notices. A dropped one is
     * turned to face whoever is looking, so it never is, and stays the single quad it was.
     */
    static EntityModel heldSprite(Texture icon) {
        return of(List.of(MeshPart.of("item",
                SpriteShape.of(icon, MODEL_BOX, SPRITE_THICKNESS * 2, MODEL_MIDDLE - SPRITE_THICKNESS))), true);
    }

    /**
     * The fallback for a dropped block whose real model would not resolve: a quarter-block cube wearing one of its
     * textures on every side, which is what the {@code ground} transform scales a block to.
     */
    static EntityModel itemBlock() {
        return of(List.of(MeshPart.of("item", List.of(MeshCube.plain(
                -DROPPED_BLOCK / 2, 0, -DROPPED_BLOCK / 2, DROPPED_BLOCK, DROPPED_BLOCK, DROPPED_BLOCK)))), true);
    }

    /**
     * And the block at the size its model states, for the same reason {@link #heldSprite} is full size.
     *
     * <p>Still one texture on every side, which is the fallback for a block whose real model could not be resolved -
     * see {@link BlockItems} for the one that reads it.
     */
    static EntityModel heldBlock() {
        return of(List.of(MeshPart.of("item",
                List.of(MeshCube.plain(0, 0, 0, MODEL_BOX, MODEL_BOX, MODEL_BOX)))), true);
    }

    /**
     * This shape as a dropped item: shrunk about the middle of its own model box by the client's {@code ground}
     * transform and stood on the ground. Shrinking the box rather than the geometry is what rests a partial block on
     * the floor instead of hovering - a slab is the lower half of its box, so its own underside ends up at the bottom.
     */
    EntityModel onGround(float scale) {
        if (parts.isEmpty()) return this;

        return of(List.of(new MeshPart("item", false, 0, MODEL_MIDDLE * scale, 0, 0, 0, 0,
                scale, scale, scale, List.of(), centred())), culled);
    }

    /**
     * This shape as one part hung off a joint of another model: at the joint the pose is measured from, turned with
     * it, and centred on the box it was authored in - which is the client's convention, since every {@code display}
     * rotation in the assets turns the item about that middle rather than about a corner.
     *
     * <p>The joint's own rotation composes in, so an item follows the arm holding it.
     *
     * @param head whether it turns with the wearer's head, which a pumpkin worn on one does and a held item does not
     */
    EntityModel onJoint(Joint joint, ItemPoses.Pose pose, boolean head) {
        if (parts.isEmpty()) return this;

        float[] reach = Turns.apply(joint.turn(), pose.offset()[0], pose.offset()[1], pose.offset()[2]);
        float[] turned = Turns.angles(Turns.times(joint.turn(),
                Turns.part(pose.rotation()[0], pose.rotation()[1], pose.rotation()[2])));

        // Hung underneath rather than flattened into one box, so a shape with rotations of its own keeps them - a
        // lectern's sloped top, an azalea's crossed planes.
        return of(List.of(new MeshPart("item", head,
                joint.x() + reach[0], joint.y() + reach[1], joint.z() + reach[2],
                turned[0], turned[1], turned[2],
                pose.scale(), pose.scale(), pose.scale(),
                List.of(), centred())), culled);
    }

    /** These parts shifted so the middle of the model box is the origin, which is what both transforms turn about. */
    private List<MeshPart> centred() {
        return moved(-MODEL_MIDDLE, -MODEL_MIDDLE, -MODEL_MIDDLE);
    }

    private List<MeshPart> moved(float dx, float dy, float dz) {
        List<MeshPart> shifted = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            shifted.add(part.moved(dx, dy, dz));
        }
        return List.copyOf(shifted);
    }

    /**
     * Highest point and widest horizontal reach of a posed subtree. The bounds only keep a search from missing the
     * model, so they are loose: part rotations are ignored, since a rotated part never reaches further from its pivot
     * than the corner-reach term already allows.
     *
     * <p>The part scales are not ignored. Vanilla registers several mobs as another's mesh scaled - a husk, a cave
     * spider, a giant - so leaving it out would search for a six-times-life-size giant at the size of a zombie.
     *
     * @param bounds min Y, max Y, max horizontal reach, all in entity pixels
     */
    private static void measure(MeshPart part, float atX, float atY, float atZ, float by, float[] bounds) {
        float x = atX + part.x() * by;
        float y = atY + part.y() * by;
        float z = atZ + part.z() * by;
        // One factor rather than three, since the reach is a radius and the tallest of them is what has to fit.
        float scale = by * Math.max(part.xScale(), Math.max(part.yScale(), part.zScale()));

        for (MeshCube cube : part.cubes()) {
            bounds[0] = Math.min(bounds[0], y + cube.minY() * scale);
            bounds[1] = Math.max(bounds[1], y + cube.maxY() * scale);
            bounds[2] = Math.max(bounds[2], Math.abs(x + cube.minX() * scale));
            bounds[2] = Math.max(bounds[2], Math.abs(x + cube.maxX() * scale));
            bounds[2] = Math.max(bounds[2], Math.abs(z + cube.minZ() * scale));
            bounds[2] = Math.max(bounds[2], Math.abs(z + cube.maxZ() * scale));
        }
        for (MeshPart child : part.children()) {
            measure(child, x, y, z, scale, bounds);
        }
    }
}
