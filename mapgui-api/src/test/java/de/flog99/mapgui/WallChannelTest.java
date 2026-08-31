package de.flog99.mapgui;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bookkeeping behind a shared picture: who holds the ids, who draws, and who is drawn for.
 *
 * <p>What is <b>not</b> here is the thing the feature is for. That six walls cost one wall's egress is a claim
 * about packets on a wire, and it is answered by {@code /mapgui performance} with two walls up rather than by
 * anything that runs without a server. These hold the parts that would be wrong quietly.
 */
class WallChannelTest {

    private static final WallLayout FOUR_BY_THREE = WallLayout.anchoredAt(0, 64, 0, BlockFace.NORTH).resized(4, 3);
    private static final WallLayout TWO_BY_TWO = WallLayout.anchoredAt(0, 64, 0, BlockFace.NORTH).resized(2, 2);

    @BeforeEach
    void forgetWhateverRanBefore() {
        WallChannel.forgetAll();
    }

    /** The whole point: two walls, one set of ids, so one send covers both. */
    @Test
    void everyWallOnAChannelHangsTheSameIds() {
        WallChannel first = WallChannel.join("lobby", FOUR_BY_THREE, null);
        int[] ids = first.ids(0, FOUR_BY_THREE.count());

        WallChannel second = WallChannel.join("lobby", FOUR_BY_THREE, null);
        assertArrayEquals(ids, second.ids(0, FOUR_BY_THREE.count()));
        assertEquals(FOUR_BY_THREE.count(), ids.length);
    }

    @Test
    void adifferentChannelIsADifferentPicture() {
        int[] lobby = WallChannel.join("lobby", FOUR_BY_THREE, null).ids(0, FOUR_BY_THREE.count());
        int[] foyer = WallChannel.join("foyer", FOUR_BY_THREE, null).ids(0, FOUR_BY_THREE.count());

        assertNotEquals(lobby[0], foyer[0], "two channels must not draw over each other");
    }

    /**
     * A wall of another size is refused rather than quietly given its own ids.
     *
     * <p>Quietly is the problem: the setting would look like it worked, and the wall would cost exactly what it
     * was meant to save while nothing anywhere said so.
     */
    @Test
    void aWallOfAnotherSizeCannotJoin() {
        WallChannel.join("lobby", FOUR_BY_THREE, null);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> WallChannel.join("lobby", TWO_BY_TWO, null));
        assertTrue(refused.getMessage().contains("4x3") && refused.getMessage().contains("2x2"),
                "the message has to name both sizes: " + refused.getMessage());
    }

    /** A second layer is minted once for the channel too, the way a prerendered loop asks for one. */
    @Test
    void furtherLayersAreSharedAsWell() {
        WallChannel channel = WallChannel.join("lobby", FOUR_BY_THREE, null);

        int[] first = channel.ids(1, FOUR_BY_THREE.count());
        assertArrayEquals(first, channel.ids(1, FOUR_BY_THREE.count()));
        assertNotEquals(first[0], channel.ids(0, FOUR_BY_THREE.count())[0]);
    }

    /**
     * A viewer of any wall is a viewer the drawing wall has to send to.
     *
     * <p>Which is the part that is easy to get wrong by only sending to your own: somebody standing at the far
     * television needs those map ids in their client, and no wall but the drawing one is going to put them there.
     */
    @Test
    void theAudienceIsEveryWallsViewersTogether() {
        WallChannel channel = WallChannel.join("lobby", FOUR_BY_THREE, null);
        UUID atOneWall = UUID.randomUUID();
        UUID atTheOther = UUID.randomUUID();

        channel.reportViewers(1, List.of());
        assertTrue(channel.viewers().isEmpty());

        channel.reportViewers(1, List.of(atOneWall));
        channel.reportViewers(1, List.of(atTheOther));

        assertEquals(Set.of(atOneWall, atTheOther), channel.viewers());
        assertTrue(channel.wants(atOneWall));
        assertTrue(channel.wants(atTheOther));
    }

    /**
     * Somebody who walked away stops being sent to, but not until the tick after - every wall has to have had
     * its say before the set is believed, and which wall ticks last is not knowable while it is being gathered.
     */
    @Test
    void aViewerWhoLeavesDropsOutOneTickLater() {
        WallChannel channel = WallChannel.join("lobby", FOUR_BY_THREE, null);
        UUID gone = UUID.randomUUID();

        channel.reportViewers(1, List.of(gone));
        channel.reportViewers(2, List.of());
        assertTrue(channel.wants(gone), "still owed a frame on the tick their absence was first noticed");

        channel.reportViewers(3, List.of());
        assertFalse(channel.wants(gone));
    }

    @Test
    void aChannelIsForgottenOnceTheLastWallLeaves() {
        WallChannel channel = WallChannel.join("lobby", FOUR_BY_THREE, null);
        int[] before = channel.ids(0, FOUR_BY_THREE.count());
        channel.leave(null);

        int[] after = WallChannel.join("lobby", FOUR_BY_THREE, null).ids(0, FOUR_BY_THREE.count());
        assertNotEquals(before[0], after[0], "a channel nobody is in must not keep its ids alive");
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
