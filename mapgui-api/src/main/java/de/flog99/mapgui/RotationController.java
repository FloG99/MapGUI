package de.flog99.mapgui;

import org.bukkit.entity.Player;

/**
 * Pushes a player's pitch back inside the usable range without disturbing their yaw.
 *
 * <p>Yaw drives the horizontal cursor, so it is left strictly alone: setting both axes at once sends a yaw
 * that is already a tick stale and snaps the player's aim sideways mid-flick. Teleporting is out too, since
 * momentum has to survive.
 *
 * <p>Only used when {@code cursor.clamp-pitch} is on; with it off the cursor stops at the edge instead.
 */
public interface RotationController {

    void setPitchKeepingYaw(Player player, float pitch);
}
