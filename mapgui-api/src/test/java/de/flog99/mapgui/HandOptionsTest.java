package de.flog99.mapgui;

import org.junit.jupiter.api.Test;
import org.bukkit.inventory.EquipmentSlot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The focus rule, which is the one piece of this worth a test: it is the whole behavioural difference between the
 * carry modes, several handlers ask it rather than working it out, and getting it wrong means a player who cannot
 * mine or a map that cannot be clicked.
 */
class HandOptionsTest {

    private static final boolean SNEAKING = true;
    private static final boolean TOGGLED = true;

    @Test
    void aPopupAlwaysHasTheMouse() {
        HandOptions popup = HandOptions.popup();

        assertTrue(popup.focused(EquipmentSlot.HAND, !SNEAKING, !TOGGLED));
        assertTrue(popup.focused(EquipmentSlot.OFF_HAND, !SNEAKING, !TOGGLED),
                "a popup claims both hands, so it does not matter which one is asked about");
    }

    /** The rule a player never has to be told: to use the map, hold it. */
    @Test
    void holdingItInTheMainHandIsEnoughOnItsOwn() {
        for (HandOptions.Focus focus : HandOptions.Focus.values()) {
            if (focus == HandOptions.Focus.NEVER) {
                continue;
            }

            assertTrue(HandOptions.item().focus(focus).focused(EquipmentSlot.HAND, !SNEAKING, !TOGGLED),
                    focus + " should not need a gesture in the main hand");
        }
    }

    @Test
    void anOffhandMapIsAPictureUntilItsGestureSaysOtherwise() {
        HandOptions swap = HandOptions.offhand().focus(HandOptions.Focus.SWAP_HANDS);

        assertFalse(swap.focused(EquipmentSlot.OFF_HAND, !SNEAKING, !TOGGLED));
        assertTrue(swap.focused(EquipmentSlot.OFF_HAND, !SNEAKING, TOGGLED));

        HandOptions sneak = HandOptions.offhand().focus(HandOptions.Focus.SNEAK);

        assertFalse(sneak.focused(EquipmentSlot.OFF_HAND, !SNEAKING, TOGGLED),
                "sneak is held rather than toggled, so a stale toggle must not reach it");
        assertTrue(sneak.focused(EquipmentSlot.OFF_HAND, SNEAKING, !TOGGLED));
    }

    /** Never means never, which is what makes it usable for something only ever looked at. */
    @Test
    void neverRefusesEvenTheMainHand() {
        HandOptions never = HandOptions.pinned(4).focus(HandOptions.Focus.NEVER);

        assertFalse(never.focused(EquipmentSlot.HAND, SNEAKING, TOGGLED));
        assertFalse(never.focused(EquipmentSlot.OFF_HAND, SNEAKING, TOGGLED));
    }

    /** Only the toggling modes should make a gesture swallow the player's click, so the rest must not claim one. */
    @Test
    void onlyTheTogglingModesClaimAGesture() {
        assertTrue(HandOptions.offhand().focus(HandOptions.Focus.SWAP_HANDS).togglesFocus());
        assertTrue(HandOptions.offhand().focus(HandOptions.Focus.RIGHT_CLICK).togglesFocus());
        assertFalse(HandOptions.offhand().focus(HandOptions.Focus.SNEAK).togglesFocus());
        assertFalse(HandOptions.popup().focus(HandOptions.Focus.RIGHT_CLICK).togglesFocus(),
                "a popup has nothing to toggle, so it must not eat a right-click for it");
    }

    /**
     * A hand-written config is the one caller that can ask for a mode that makes no sense, so the shape is made
     * consistent rather than trusted.
     */
    @Test
    void anImpossibleModeIsMadePossible() {
        HandOptions offSlot = new HandOptions(HandOptions.Carry.PINNED, HandOptions.Focus.MAIN_HAND, 47, false, false, 0).sane();
        assertEquals(8, offSlot.slot(), "there are nine hotbar slots and 47 is not one of them");

        HandOptions confusedPopup = new HandOptions(HandOptions.Carry.POPUP, HandOptions.Focus.MAIN_HAND, 0, true, true, 0).sane();
        assertFalse(confusedPopup.offhandAllowed(), "a popup reports the offhand empty to draw itself large");
        assertFalse(confusedPopup.movable(), "and is in every slot, so there is nowhere to move it to");

        HandOptions offhand = new HandOptions(HandOptions.Carry.OFFHAND, HandOptions.Focus.SWAP_HANDS, 0, false, false, 0).sane();
        assertTrue(offhand.offhandAllowed(), "the offhand is where it lives, whatever the flag said");
    }

    @Test
    void whichSlotsAreFakedFollowsWhereTheMapIs() {
        for (int slot = 0; slot < 9; slot++) {
            assertTrue(MapSlots.wholeHotbar().shows(slot));
        }
        assertEquals(MapSlots.Offhand.EMPTY, MapSlots.wholeHotbar().offhand(),
                "an occupied offhand shrinks the map into a corner of the screen");

        MapSlots one = MapSlots.hotbar(3);
        assertTrue(one.shows(3));
        assertFalse(one.shows(4), "the player keeps the rest of their hotbar");
        assertEquals(MapSlots.Offhand.REAL, one.offhand());

        assertFalse(MapSlots.offhandOnly().shows(0), "the whole hotbar is the player's own");
        assertFalse(MapSlots.none().any(), "a real item is in the slot, so nothing is pretended about it");
    }

    /**
     * A pinned map id survives every other change, and a nonsense one is dropped rather than drawn to.
     *
     * <p>The floor is the point: a low number is a real map somebody owns, and painting it replaces their picture.
     */
    @Test
    void aPinnedMapIdIsKeptAndAnImpossibleOneIsNot() {
        HandOptions pinned = HandOptions.item().mapId(Integer.MAX_VALUE - 1);
        assertEquals(Integer.MAX_VALUE - 1, pinned.mapId());
        assertEquals(Integer.MAX_VALUE - 1, pinned.focus(HandOptions.Focus.ALWAYS).movable(false).sane().mapId(),
                "changing anything else leaves the id alone");

        assertEquals(HandOptions.ANY_MAP_ID, HandOptions.popup().mapId(), "nothing is pinned unless it is asked for");
        assertEquals(HandOptions.ANY_MAP_ID, HandOptions.item().mapId(-7).sane().mapId(),
                "a negative id is no id, not a map somebody owns");
    }
}
