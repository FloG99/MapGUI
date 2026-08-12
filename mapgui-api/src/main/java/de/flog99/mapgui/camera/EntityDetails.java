package de.flog99.mapgui.camera;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * What a mob is drawn from that the server keeps and Bukkit does not hand over.
 *
 * <p>Needs the server internals, like {@link de.flog99.mapgui.map.SavedMapPixels}, and fails the same way: a fork
 * that has moved them answers nothing, and the mob is drawn the way it was before any of this.
 */
@ApiStatus.Internal
public interface EntityDetails {

    /**
     * How a squid is pointing, in degrees, as {@code SquidRenderer} turns it: how far its body is tipped over about
     * X, and how far round it has rolled about its own axis.
     *
     * <p>Its renderer does not read the yaw and pitch every other mob is turned by - it reads two fields the squid
     * keeps, eased toward wherever it is jetting a tenth per tick. So a squid that has stopped still points where it
     * was going, and nothing about its position or velocity says where that is.
     *
     * @return {@code {tip, roll}}, or null for anything that is not a squid and for a server that will not say
     */
    float[] swimming(Entity entity);

    /**
     * Whether an iron golem is holding a poppy out, which it does for twenty seconds after offering one.
     *
     * <p>Not in Bukkit at all: the countdown is the golem's own field, and a client only ever hears it as the entity
     * event that starts and stops the pose.
     */
    boolean offeringFlower(Entity entity);
}
