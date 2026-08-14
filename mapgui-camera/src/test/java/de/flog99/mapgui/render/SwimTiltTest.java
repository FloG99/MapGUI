package de.flog99.mapgui.render;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Which way round a squid ends up.
 *
 * <p>Two frames meet and the wrong one is the tempting one. A mesh is a half circle about Z from the space vanilla's
 * model classes are written in, so a rest pose stated there changes sign here - but the two angles a squid's renderer
 * turns one by are not stated there. They are applied outside that flip, alongside the half block it turns a squid
 * about, so they come across as they are. Reading the flip into the tip left every squid a half circle wrong:
 * tentacles leading, mantle astern.
 *
 * <p>Measured rather than argued about, because a squid is hard to catch at it - at the angle one swims at the wrong
 * sign is still level and still moving along its own axis, so it reads as a squid until you look at which end is which.
 */
class SwimTiltTest {

    /** Where the client turns a squid about, and how far each marker reaches from that point. */
    private static final float PIVOT = 8;

    private static final float REACH = 8;

    /**
     * A point where a squid's mantle is - straight up from the pivot - and one where a fin is, out to its right. Parts
     * rather than boxes, so that where each ends up is read off the model the way the tracer reads a joint.
     */
    private static EntityModel marked() {
        List<MeshCube> speck = List.of(MeshCube.plain(0, 0, 0, 1, 1, 1));
        return EntityModel.of(List.of(
                MeshPart.at("mantle", 0, PIVOT + REACH, 0, speck, List.of()),
                MeshPart.at("fin", REACH, PIVOT, 0, speck, List.of())));
    }

    /**
     * One jetting along level, which is the quarter circle back a squid holds while it is swimming and the one a
     * beached squid eases to. Its mantle leads, which is local -Z, and its fins stay out to the sides.
     */
    @Test
    void aSquidSwimmingLevelLeadsWithItsMantle() {
        EntityModel level = marked().swimming((float) Math.toRadians(-90), 0, PIVOT);

        assertJoint(level, "mantle", 0, PIVOT, -REACH);
        assertJoint(level, "fin", REACH, PIVOT, 0);
    }

    /**
     * And one pointing straight down, spun a quarter circle about its own axis - which for a squid heading for the sea
     * floor is about the vertical, so a fin goes round rather than under.
     *
     * <p>The spin is a turn about Y <i>inside</i> the tip. Applied beside it as a turn about Z - which is the same
     * rotation for a squid swimming level, and the reason that shortcut survives the test above - the fin ends up
     * beneath the animal instead.
     */
    @Test
    void theSpinTurnsAboutTheBodyRatherThanTheWorld() {
        EntityModel diving = marked().swimming((float) Math.toRadians(-180), (float) Math.toRadians(90), PIVOT);

        assertJoint(diving, "mantle", 0, PIVOT - REACH, 0);
        assertJoint(diving, "fin", 0, PIVOT, REACH);
    }

    private static void assertJoint(EntityModel model, String part, float x, float y, float z) {
        EntityModel.Joint at = model.joint(part);
        assertNotNull(at, part + " is in the model");

        assertEquals(x, at.x(), 0.01, part + ", across");
        assertEquals(y, at.y(), 0.01, part + ", up");
        assertEquals(z, at.z(), 0.01, part + ", along");
    }
}
