package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntitySnapshot;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The cap used to be applied per column as well as overall, which dropped whichever ones a chunk listed last. */
class BlockEntityCaptureTest {

    private static final Location EYE = new Location(null, 0, 64, 0);

    private static EntitySnapshot at(double x) {
        return new EntitySnapshot(x, 64, 0, 0, 0, 0, 1, null, "chest");
    }

    /** Furthest away is what a cap may drop. Anything else is a chest missing from the middle of a wall. */
    @Test
    void theCapKeepsTheNearestAndDropsTheFurthest() {
        List<EntitySnapshot> drawn = new ArrayList<>(List.of(at(40), at(5), at(90), at(20)));

        List<EntitySnapshot> kept = BlockEntityCapture.nearest(drawn, EYE, 2);

        assertEquals(2, kept.size());
        assertEquals(5, kept.get(0).x());
        assertEquals(20, kept.get(1).x());
    }

    /** Under the cap nothing is sorted or copied, since that is every capture but the one in a storage room. */
    @Test
    void underTheCapTheListComesBackUntouched() {
        List<EntitySnapshot> drawn = new ArrayList<>(List.of(at(90), at(5)));

        assertSame(drawn, BlockEntityCapture.nearest(drawn, EYE, 8));
    }
}
