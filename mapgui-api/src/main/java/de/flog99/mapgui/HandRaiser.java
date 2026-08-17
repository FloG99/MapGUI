package de.flog99.mapgui;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Tells one client that it is using what it is holding, which is the only way to read a held button.
 *
 * <p>A client says nothing about a button being down. It repeats the press every four ticks and reports nothing at
 * all when it comes up - so a hold can only be guessed at from the gaps. The one thing it does report is letting go
 * of an item it was <b>using</b>: a client that believes it is using something sends exactly one message, at exactly
 * the moment the button is released, and stops repeating in the meantime.
 *
 * <p><b>Believing it has to come from here rather than from the item</b>, and that is the whole point. A client that
 * starts a use of its own accord - because the item is food, a shield, anything at all - drops the held item to the
 * bottom of the screen and springs it back, vanilla's "you used that" bob. On a map that bob <i>is</i> the screen,
 * jumping once per press. Told from outside, through the flag the server syncs for every other entity,
 * {@code LocalPlayer} starts the use itself and no animation is played at all: only {@code Minecraft.startUseItem}
 * ever bobs the hand, and that is the path this avoids.
 *
 * <p>Nothing but that one client's opinion. The server is using nothing, no item is consumed, no cooldown is
 * started and no other player is told. It ends when the client says the button came up, or when this says so.
 */
public interface HandRaiser {

    /**
     * @param hand   which hand to raise, which is the one the screen is in. The other holds the player's own item,
     *               and a client told to use that one would use it - scoping a spyglass, drawing a bow, eating
     * @param raised whether the client should believe it is using what is in that hand. A hand holding nothing
     *               ignores it, having nothing to use
     */
    void raise(Player player, EquipmentSlot hand, boolean raised);
}
