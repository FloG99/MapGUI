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
 * @param floor  the lowest point any of its cubes reaches, in the same pixels, which is not always zero - a ghast's
 *               tentacles hang below where it stands, and a dropped item is stood on whatever this says
 * @param radius widest horizontal reach in blocks, likewise
 * @param culled whether only the near side of a cube may draw. False for a mob, since vanilla draws entity models
 *               with culling off and several rely on it - a chicken's leg is textured on one face and its underside,
 *               so culled it is a hole with the far side showing through. True for the things vanilla draws with a
 *               culling render type, where a flat quad carries the same picture mirrored on its back
 */
record EntityModel(List<MeshPart> parts, float height, float floor, float radius, boolean culled) {

    private static final float PIXEL = 1 / 16f;

    /** Vanilla's inflation for the hat, and for the other six overlay parts. */
    private static final float HAT_GROW = 0.5f;
    private static final float OVERLAY_GROW = 0.25f;

    /** Half of the pixel the vanilla item quad is thick, since the extrusion is measured either side of the middle. */
    private static final float SPRITE_THICKNESS = 0.5f;

    /** The box every model is authored in, item and block alike, in the sixteenths it states its own coordinates in. */
    private static final float MODEL_BOX = 16;

    /**
     * And the middle of it, which is where a held thing is turned about - the client's own convention rather than a
     * choice here. The difference shows on anything that does not fill its box: a slab centred on its own geometry
     * sits half a block too high.
     */
    private static final float MODEL_MIDDLE = MODEL_BOX / 2;

    /** What the {@code ground} transform scales a block to, which is a quarter of the model box. */
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
        return tilted(xRot, 0, zRot, pivotY);
    }

    /**
     * The same with a turn about the vertical as well, which is what a block entity lying on one of the six faces of
     * its block needs - a shulker box on a wall is a quarter circle over on its side and then round to face out of it.
     */
    EntityModel tilted(float xRot, float yRot, float zRot, float pivotY) {
        if (xRot == 0 && yRot == 0 && zRot == 0) return this;

        return of(List.of(new MeshPart("tilt", false, 0, pivotY, 0, xRot, yRot, zRot, 1, 1, 1,
                List.of(), moved(0, -pivotY, 0))), culled);
    }

    /**
     * The same model tipped over about X and then spun about its own axis, which is how a squid's renderer points one
     * along whatever it is jetting: the spin sits <i>inside</i> the tip, so it carries the mantle round the way the
     * animal is going rather than tipping it further over.
     *
     * <p>Composed and taken back apart rather than handed straight to {@link #tilted}, because a part applies its own
     * three angles Z, then Y, then X - the other order - and no triple in that order is this rotation. For a squid
     * swimming level it falls out as the tip and a turn about Z, which is where the two orders happen to agree.
     *
     * @param pivotY in entity pixels off the feet
     */
    EntityModel swimming(float tip, float spin, float pivotY) {
        float[] angles = Turns.angles(Turns.times(Turns.x(tip), Turns.y(spin)));
        return tilted(angles[0], angles[1], angles[2], pivotY);
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
        float floor = bounds[0] == Float.MAX_VALUE ? 0 : bounds[0];
        float down = Math.max(Math.abs(height - middle), Math.abs(floor - middle));
        return new EntityModel(List.copyOf(parts), height, floor, (float) Math.hypot(across, down) * PIXEL, culled);
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

    /**
     * This model with both arms out in front, which is how an enderman stands while it is carrying a block. Its own
     * model does it and nothing else here holds anything out.
     *
     * <p>{@code EndermanModel.setupAnim} <b>sets</b> both arms rather than shifting them, so whatever the walk left
     * them at is discarded - unlike {@link #crouched}, which leans off it. Same sign rule though: vanilla's -0.5
     * about X is +0.5 here, and the small spread about Z carries over unchanged.
     */
    EntityModel carrying() {
        return of(carry(parts), culled);
    }

    private static List<MeshPart> carry(List<MeshPart> parts) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            MeshPart posed = switch (part.name()) {
                case "right_arm" -> part.withRotation(CARRY_ARM_LEAN, part.yRot(), CARRY_ARM_SPREAD);
                case "left_arm" -> part.withRotation(CARRY_ARM_LEAN, part.yRot(), -CARRY_ARM_SPREAD);
                default -> part;
            };
            out.add(posed.withChildren(carry(posed.children())));
        }
        return List.copyOf(out);
    }

    /** Radians, and the client's own number with the sign this frame gives it. */
    private static final float CARRY_ARM_LEAN = 0.5f;

    /** And a little apart, so the two do not read as one limb from the front. */
    private static final float CARRY_ARM_SPREAD = 0.05f;

    /**
     * This model with its right arm held out and its left one down, which is how an iron golem stands while it is
     * offering a poppy.
     *
     * <p>One arm, not both: {@code IronGolemModel.setupAnim} sets {@code rightArm.xRot} to -0.8 and {@code leftArm}
     * flat, so the empty arm stops swinging rather than coming up too. Its wobble of a fortieth of a radian is left
     * out, and so is the attack pose that would override both.
     */
    EntityModel offering() {
        return of(offer(parts), culled);
    }

    private static List<MeshPart> offer(List<MeshPart> parts) {
        List<MeshPart> out = new ArrayList<>(parts.size());
        for (MeshPart part : parts) {
            MeshPart posed = switch (part.name()) {
                case "right_arm" -> part.withRotation(OFFER_ARM_LEAN, part.yRot(), part.zRot());
                case "left_arm" -> part.withRotation(0, part.yRot(), part.zRot());
                default -> part;
            };
            out.add(posed.withChildren(offer(posed.children())));
        }
        return List.copyOf(out);
    }

    /** Radians, and vanilla's own -0.8 with the sign this frame gives it. */
    private static final float OFFER_ARM_LEAN = 0.8f;

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
     * A painting, as the client builds one: a slab a sixteenth of a block thick, as many blocks across and up as the
     * variant is, centred on where the entity stands.
     *
     * <p>Two of these make a whole painting, because the front and the back wear different textures and a snapshot
     * samples one. The picture goes on the model's -Z side, which is the side a yaw points outward - the same
     * convention a mob's face follows.
     *
     * @param front whether this is the picture or the planks behind and around it
     */
    static EntityModel painting(int blocksWide, int blocksHigh, boolean front) {
        float acrossHalf = blocksWide * MODEL_MIDDLE;
        float upHalf = blocksHigh * MODEL_MIDDLE;

        float[][] faces = new float[6][];
        for (Direction side : Direction.values()) {
            if ((side == Direction.NORTH) == front) {
                faces[side.ordinal()] = MeshCube.whole();
            }
        }

        MeshCube slab = new MeshCube(-acrossHalf, -upHalf, -PAINTING_HALF_THICKNESS,
                acrossHalf, upHalf, PAINTING_HALF_THICKNESS, faces);
        return of(List.of(MeshPart.of("painting", List.of(slab))), true);
    }

    /** Half of vanilla's own sixteenth of a block, since the slab is measured either side of the hanging point. */
    private static final float PAINTING_HALF_THICKNESS = 0.5f;

    /**
     * A flat picture facing out of an item frame or a sign: a block-wide slab with one side drawn.
     *
     * <p>Built rather than extruded because there is nothing to extrude - a map's pixels and a sign's lettering are
     * not in the assets at all, they are painted per capture and handed straight to the atlas.
     *
     * <p>Drawn on the model's +Z side, which is the side a frame and a sign both face once the yaw a block entity
     * takes has turned them - the opposite of a mob, whose face is on its -Z.
     *
     * @param across half its width, in entity pixels, and {@code up} half its height
     * @param out    how far in front of the model's own origin it sits
     * @param spin   how far round it is turned in its own plane, in radians
     */
    static EntityModel picture(float across, float up, float out, float spin) {
        float[][] faces = new float[6][];
        faces[Direction.SOUTH.ordinal()] = MeshCube.whole();

        MeshCube slab = new MeshCube(-across, -up, out - PICTURE_HALF_THICKNESS,
                across, up, out + PICTURE_HALF_THICKNESS, faces);
        return of(List.of(new MeshPart("picture", false, 0, 0, 0, 0, 0, spin,
                1, 1, 1, List.of(slab), List.of())), true);
    }

    /** Thin enough to read as flat and thick enough for a ray to meet, which is the painting's own thickness. */
    private static final float PICTURE_HALF_THICKNESS = 0.5f;

    /**
     * The picture at the size and place the item model states, extruded along its own outline.
     *
     * <p>Built from the icon rather than from its frame - see {@link SpriteShape} - because an item is seen edge on
     * often enough that where its one pixel of thickness sits is something anybody notices. Full size, since a held
     * item is scaled by its {@code thirdperson} transform and a dropped one by {@link #onGround}, and starting from a
     * halved shape would apply both.
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
     * transform and stood a pixel clear of the floor. Shrinking the box rather than the geometry is what rests a
     * partial block on the floor instead of hovering - a slab is the lower half of its box, so its own underside ends
     * up at the bottom.
     *
     * <p>The clearance is measured off the geometry rather than off the box, which is {@code ItemEntityRenderer}'s own
     * arithmetic: it takes the model's bounding box after the transform, lifts by its lowest point and then adds a
     * sixteenth. So the {@code ground} translation never has to be read - whatever it moves the shape by, this puts it
     * back and leaves exactly the one pixel.
     */
    EntityModel onGround(float scale) {
        if (parts.isEmpty()) return this;

        float lift = (MODEL_MIDDLE - floor) * scale + GROUND_CLEARANCE;
        return of(List.of(new MeshPart("item", false, 0, lift, 0, 0, 0, 0,
                scale, scale, scale, List.of(), centred())), culled);
    }

    /** The pixel {@code ItemEntityRenderer} keeps between a dropped item and the floor, so it never quite touches. */
    private static final float GROUND_CLEARANCE = 1;

    /**
     * This block model riding in a minecart, at the place the cart's own renderer puts it.
     *
     * <p>Vanilla's three steps in one part: three quarters of its size, turned a quarter circle about Y, and lifted by
     * the offset the cart states on top of the 0.375 blocks the cart itself stands at. The quarter turn is the client's
     * own and runs backwards here for the same reason a boat's does, plus a half circle for the one a block model
     * already carries - see {@link BlockItems} - which comes to a quarter the other way.
     *
     * @param offset how far up the block sits inside the cart, in entity pixels. Six for nearly every cart, and the
     *               reason a tnt charge stands proud of the rim
     */
    EntityModel inCart(float offset) {
        if (parts.isEmpty()) return this;

        return of(List.of(new MeshPart("display", false, 0, CART_HEIGHT + CART_SCALE * offset, 0,
                0, (float) -Math.PI / 2, 0, CART_SCALE, CART_SCALE, CART_SCALE, List.of(), centred())), culled);
    }

    /** What a minecart's renderer shrinks the block it carries to, and how far off the ground the cart itself sits. */
    private static final float CART_SCALE = 0.75f;

    private static final float CART_HEIGHT = 0.375f * 16;

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
        MeshPart item = new MeshPart("item", false,
                reach[0], reach[1], reach[2],
                turned[0], turned[1], turned[2],
                pose.scale(), pose.scale(), pose.scale(),
                List.of(), centred());

        // Two parts rather than one, and the offset on the inner: a head turn is applied to a part about that part's
        // own pivot, so an item carrying its offset in its own position turns on the spot where the head carries it
        // round. Invisible on anything sitting on the crown - a pumpkin's offset is straight up, and every way round
        // looks alike - and a banner standing a quarter block behind the head is twenty pixels wide and two deep, so
        // on the spot it sweeps its own width past the face. The pivot belongs at the joint, which is where the head
        // really turns.
        return of(List.of(new MeshPart("joint", head,
                joint.x(), joint.y(), joint.z(),
                0, 0, 0, 1, 1, 1,
                List.of(), List.of(item))), culled);
    }

    /**
     * This mesh standing in the middle of an item model's box, so that everything drawn from an item model can carry
     * it.
     *
     * <p>A mesh is built about its own middle and an item model is measured from a corner, which is the whole of the
     * difference - and it is the client's own translation too: {@code items/player_head.json} states a transformation
     * of half a block on both horizontal axes and nothing else.
     */
    EntityModel inItemBox() {
        return of(moved(MODEL_MIDDLE, 0, MODEL_MIDDLE), culled);
    }

    /**
     * This mesh placed inside an item's box the way that item's definition says.
     *
     * <p>For the shapes the client draws in code, whose definition carries a translation, a scale and a pair of
     * quaternions rather than any geometry - see {@link ItemDefinitions.Special}. Applied to the mesh as the client
     * built it, since that is the space the transform is stated against, and not centred on anything: where in the box
     * it goes is the whole of what the translation is saying.
     */
    EntityModel placedBy(ItemDefinitions.Special special) {
        if (parts.isEmpty()) return this;

        float[] angles = Turns.angles(special.turn());
        float[] offset = special.offset();
        return of(List.of(new MeshPart("special", false, offset[0], offset[1], offset[2],
                angles[0], angles[1], angles[2],
                special.scale(), special.scale(), special.scale(), List.of(), parts)), culled);
    }

    /**
     * This block model centred on its own box, which is what {@code ItemFrameRenderer} does to the frame before it
     * draws one: a model is measured from a corner and everything the frame is turned about is its middle.
     */
    EntityModel centredInBox() {
        return of(centred(), culled);
    }

    /**
     * This item hanging in an item frame, at the place and size the client hangs one.
     *
     * <p>Vanilla's chain, innermost first: the model centred on its own box, its own {@code fixed} transform, half
     * size, turned by whichever eighth of a circle the frame was clicked round to, and pushed out to the front of the
     * backplate. Two parts rather than four, because a part is already a scale then a rotation then a translation -
     * which is the same order the client applies them in.
     *
     * <p>The push and the turn are stated the other way round from the client's, because a block model arrives here
     * turned a half circle about Y - see {@link Turns#halfTurned}. The {@code fixed} pose has already come through
     * that same turn.
     *
     * @param spin how far round the frame has been turned, in radians, in the client's own sign
     */
    EntityModel inFrame(ItemPoses.Pose fixed, float spin) {
        if (parts.isEmpty()) return this;

        List<MeshPart> posed = List.of(new MeshPart("fixed", false,
                fixed.offset()[0], fixed.offset()[1], fixed.offset()[2],
                fixed.rotation()[0], fixed.rotation()[1], fixed.rotation()[2],
                fixed.scale(), fixed.scale(), fixed.scale(), List.of(), centred()));

        return of(List.of(new MeshPart("framed", false, 0, 0, -FRAME_FRONT, 0, 0, -spin,
                FRAMED_SCALE, FRAMED_SCALE, FRAMED_SCALE, List.of(), posed)), culled);
    }

    /** How far out of the block's middle the front of a frame's backplate is, in entity pixels. */
    private static final float FRAME_FRONT = 0.4375f * MODEL_BOX;

    /** And what the client draws a framed item at, which is the one number in that chain that is not the model's. */
    private static final float FRAMED_SCALE = 0.5f;

    /**
     * This item standing on a shelf, at the place and size the client stands one.
     *
     * <p>Vanilla's chain again: the model centred on its own box, its own {@code on_shelf} transform, then lifted so
     * that its <i>own</i> middle sits on the shelf's point - which is why the lift is measured off the posed shape
     * rather than stated. A tall item and a flat one hang off the same point that way, which is what the client does
     * and what a fixed offset would not.
     *
     * @param offset where in the block this slot is, in entity pixels, in the client's own axes - turned here for the
     *               reason {@link #inFrame} gives
     */
    EntityModel onShelf(ItemPoses.Pose shelf, float[] offset) {
        if (parts.isEmpty()) return this;

        float[] slot = Turns.halfTurned(offset[0], offset[1], offset[2]);

        EntityModel posed = of(List.of(new MeshPart("on_shelf", false,
                shelf.offset()[0], shelf.offset()[1], shelf.offset()[2],
                shelf.rotation()[0], shelf.rotation()[1], shelf.rotation()[2],
                shelf.scale(), shelf.scale(), shelf.scale(), List.of(), centred())), culled);

        float lift = -posed.floor() - (posed.height() - posed.floor()) / 2;
        List<MeshPart> raised = List.of(new MeshPart("middle", false, 0, lift, 0, 0, 0, 0,
                1, 1, 1, List.of(), posed.parts()));

        return of(List.of(new MeshPart("slot", false, slot[0], slot[1], slot[2], 0, 0, 0,
                SHELF_SCALE, SHELF_SCALE, SHELF_SCALE, List.of(), raised)), culled);
    }

    /** What a shelf shrinks what is standing on it to. */
    private static final float SHELF_SCALE = 0.25f;

    /**
     * The same shape turned a half circle about the middle of its model box, which is the whole difference between
     * the frame a mesh is built in and the frame a block model is - see {@link BlockItems}.
     */
    EntityModel halfTurned() {
        return of(List.of(new MeshPart("turned", false, MODEL_MIDDLE, MODEL_MIDDLE, MODEL_MIDDLE,
                0, (float) Math.PI, 0, 1, 1, 1, List.of(), centred())), culled);
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
