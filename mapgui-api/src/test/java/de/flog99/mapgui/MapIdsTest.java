package de.flog99.mapgui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The band at the top of the range that MapGUI promises not to draw to.
 *
 * <p>Worth a test because the promise is what a resource pack is written against: a pack keying on
 * {@code Integer.MAX_VALUE - 1} is wrong the moment this counter is allowed to reach it, and it would be wrong
 * quietly - the second GUI opened after a restart would take the phone's number, and the phone would start wearing
 * whatever that screen drew.
 */
class MapIdsTest {

    @Test
    void theCounterNeverReachesThePinnableBand() {
        for (int i = 0; i < 100; i++) {
            assertTrue(MapIds.next() < MapIds.LOWEST_PINNABLE,
                    "an id handed out inside the band a plugin was told it could keep");
        }
    }

    @Test
    void everySurfaceGetsAnIdOfItsOwn() {
        int first = MapIds.next();
        int second = MapIds.next();

        assertEquals(first - 1, second, "two surfaces on one id is one drawn with the other's pixels");
    }

    /** A thousand of them, and the top one is {@code Integer.MAX_VALUE} itself, which is the obvious one to reach for. */
    @Test
    void theBandIsWhereTheDocsSayItIs() {
        assertEquals(1024, MapIds.RESERVED);
        assertEquals(Integer.MAX_VALUE - 1023, MapIds.LOWEST_PINNABLE);
        assertTrue(MapIds.LOWEST_PINNABLE <= Integer.MAX_VALUE - 1, "MAX_VALUE - 1 is pinnable, which is what the docs offer");
    }
}
