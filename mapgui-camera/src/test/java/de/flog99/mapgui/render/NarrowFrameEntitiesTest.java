package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entities in a frame that is a <b>window</b> rather than a field of view, which is what a mirror asks for.
 *
 * <p>{@link EntityScreen} keeps entities affordable by testing each pixel only against the ones whose projected rect
 * covers it, and that works because a rect is small when the frame is wide. A mirror's frame is not wide: it is fitted
 * to the glass, so a 2x2 mirror seen from twenty blocks is a window five degrees across - a telephoto shot - and in one
 * of those anything within a block of the axis covers a large share of the picture however far off it is. A chest thirty
 * blocks down that tube covers a third of the frame.
 *
 * <p>So the rect stopped selecting, and every pixel of a reflection walked every entity in the tube: a player is about
 * thirty boxes over eight parts, all of them tested however far the ray passes. Measured at 256x256, twenty players
 * down the axis of a five degree frame cost <b>eight times the whole rest of the frame</b>, where the same twenty in a
 * ninety degree frame cost nothing measurable. A 2x2 mirror across the room was taking 870 ms a frame.
 *
 * <p>Two things fixed it, and this holds the picture still across both: a bounding test before any mesh is walked - see
 * {@code EntityTracer#nearEnough} - and handing the candidates over nearest first, so that once a ray has met something
 * opaque everything behind it is turned away by that test rather than walked. The cost of the second is that entity
 * order now matters to the work done, and must go on not mattering to the result.
 */
class NarrowFrameEntitiesTest {

    /** Five and a bit degrees across, which is a 2x2 mirror seen from twenty blocks. */
    private static final double TELEPHOTO = 0.05;

    private static final int SIZE = 64;

    private static final String SKIN = "skin";

    private static TestWorld nothing() {
        return new TestWorld().texture(SKIN, TestWorld.solid(0xFFCC8844));
    }

    /** Looking along +x, level, from the origin. */
    private static CameraView looking(double tangent) {
        return new CameraView(0, 70, 0, -90, 0, 70, 96, false, null,
                CameraView.Lens.of(-tangent, tangent, -tangent, tangent), null);
    }

    private static EntitySnapshot standing(double along, double aside) {
        return new EntitySnapshot(along, 69, aside, 0, 0, 0, 1f,
                EntityModel.player(false, SkinLayers.ALL, false), SKIN);
    }

    private static int[] rendered(TestWorld world, CameraView view, List<EntitySnapshot> entities) {
        int[] out = new int[SIZE * SIZE];
        new RayCaster(world, Canopy.DEFAULT).render(world, view, entities, SIZE, SIZE, out);
        return out;
    }

    private static int drawn(int[] frame) {
        int count = 0;
        for (int pixel : frame) {
            if (pixel != TestWorld.SKY) {
                count++;
            }
        }
        return count;
    }

    /**
     * A distant player is still drawn at the size the geometry says, rather than partly turned away.
     *
     * <p>The one thing a bounding test can get wrong is refusing a ray that would have drawn something, and a telephoto
     * frame is where it would show: this window covers 2 blocks each way at twenty blocks, and a player standing there
     * is 0.6 wide and 1.8 tall, so their <b>torso alone</b> - half a block across and a block and a quarter of it inside
     * the frame - is a sixth of the picture. Their arms, legs and head take it to about a fifth, which is what this
     * draws: 776 pixels of 4096. An eighth is the floor, because a floor derived from the parts that cannot be missing
     * is one that stays true if a player model is ever posed differently.
     *
     * <p>What it can and cannot catch, since that was checked rather than assumed: the bounding sphere is a generous
     * bound - two blocks of radius round a mesh that needs about one - so shrinking it by half changes nothing here and
     * nothing on screen either. At 0.3 of it the sphere cuts into the model and this drops to 416 pixels and fails. So
     * it holds the line where the line is: a bound tight enough to clip the thing it is bounding.
     */
    @Test
    void aDistantPlayerStillFillsTheirShareOfATelephotoFrame() {
        TestWorld world = nothing();
        int[] frame = rendered(world, looking(TELEPHOTO), List.of(standing(20, 0)));

        int share = drawn(frame);
        assertTrue(share > SIZE * SIZE / 8,
                "a player across the room should be a fifth of a five degree frame, and only " + share
                        + " pixels of " + SIZE * SIZE + " were drawn");
        assertTrue(share < SIZE * SIZE / 2, "and not half of it, or this is measuring a sky that failed to draw");
    }

    /**
     * The picture does not depend on which order the entities were gathered in.
     *
     * <p>Which it must not, now that the order decides how much work is done: a capture hands them over as it found
     * them - mobs, then the chests, then MapGUI's own walls - and the screen sorts them by depth so the near ones are
     * met first. {@link Fragments} orders what it is given by depth however it arrives, and only an opaque texel
     * shortens the ray, so the frame is the same either way. This is what says so.
     */
    @Test
    void theSameFrameWhateverOrderTheEntitiesArriveIn() {
        TestWorld world = nothing();
        CameraView view = looking(TELEPHOTO);

        List<EntitySnapshot> gathered = new ArrayList<>();
        for (int at = 0; at < 5; at++) {
            gathered.add(standing(6 + at * 7, 0.2 * at));
        }

        int[] nearestFirst = rendered(world, view, List.copyOf(gathered));
        Collections.reverse(gathered);
        int[] furthestFirst = rendered(world, view, List.copyOf(gathered));

        assertArrayEquals(nearestFirst, furthestFirst,
                "the reflection changed when the entities were handed over in the other order");
    }

    /**
     * Somebody standing behind an opaque body contributes nothing, which is what makes the ordering worth having.
     *
     * <p>The near player fills this frame - 0.6 blocks wide against a window 0.5 wide at five blocks - so the far one is
     * hidden completely, and the frame has to be identical to the one without them. If it is not, either the bounding
     * test is turning away the near player or the limit is not being applied to the far one.
     */
    @Test
    void anEntityHiddenBehindAnotherChangesNothing() {
        TestWorld world = nothing();
        CameraView view = looking(TELEPHOTO);

        int[] alone = rendered(world, view, List.of(standing(5, 0)));
        int[] withOneBehind = rendered(world, view, List.of(standing(5, 0), standing(40, 0)));

        assertTrue(drawn(alone) > SIZE * SIZE / 2, "the near player should be filling this frame");
        assertArrayEquals(alone, withOneBehind,
                "somebody hidden behind a solid body changed the picture");
    }
}
