package de.flog99.mapgui;

import de.flog99.mapgui.camera.EntityAngles;
import de.flog99.mapgui.map.SavedMapPixels;
import org.jetbrains.annotations.ApiStatus;

/**
 * Everything MapGUI needs from inside the server, for one Minecraft version.
 *
 * <p>Only four things touch {@code net.minecraft}: pixels and the map item on their way out, two gestures on their
 * way in, a pitch nudge, and the world's saved map pixels. Each is its own interface already, so a version is an
 * implementation of each and this to hand them over.
 *
 * <p><b>Adding a version.</b> Copy the newest {@code mapgui-nms-*} module, point its dev bundle at the new Paper,
 * fix what the compiler complains about, and add the module to {@code settings.gradle.kts}, to the plugin's
 * dependencies and to the version table in {@code Backends}. Nothing above this interface knows which version it is
 * running on.
 *
 * <p>Implementations are found by name at runtime rather than linked, since each is compiled against its own server
 * jar and only one can load on any given server.
 */
@ApiStatus.Internal
public interface ServerBackend {

    MapTransport transport();

    PacketInput input();

    RotationController rotation();

    SavedMapPixels savedMapPixels();

    EntityAngles entityAngles();
}
