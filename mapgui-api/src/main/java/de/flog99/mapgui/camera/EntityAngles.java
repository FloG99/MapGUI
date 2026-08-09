package de.flog99.mapgui.camera;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.ApiStatus;

/**
 * The angles a mob is drawn at that the server keeps and Bukkit does not hand over.
 *
 * <p>There is one of these, and it is the squid. Its renderer does not read the yaw and pitch every other mob is
 * turned by - it reads two fields the squid itself keeps, which it eases toward wherever the animal is jetting a
 * tenth of the way per tick. So a squid that has stopped is still pointing wherever it was going, and nothing about
 * its position or its velocity says where that is.
 *
 * <p>Needs the server internals, like {@link de.flog99.mapgui.map.SavedMapPixels}, and fails the same way: a fork
 * that has moved them answers nothing and the squid is drawn upright, which is what it was drawn as before.
 */
@ApiStatus.Internal
public interface EntityAngles {

    /**
     * How a squid is pointing, in degrees, as {@code SquidRenderer} turns it: how far its body is tipped over about
     * X, and how far round it has rolled about its own axis.
     *
     * @return {@code {tip, roll}}, or null for anything that is not a squid and for a server that will not say
     */
    float[] swimming(Entity entity);
}
