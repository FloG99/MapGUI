package de.flog99.mapgui.nms.v26_2;

import de.flog99.mapgui.MapTransport;
import de.flog99.mapgui.PacketInput;
import de.flog99.mapgui.RotationController;
import de.flog99.mapgui.ServerBackend;
import de.flog99.mapgui.map.SavedMapPixels;

/**
 * Minecraft 26.2.
 *
 * <p>Found by name, so this class and its package are what the version table points at. Everything it hands
 * back is built here and kept, since a transport counts the bytes it has sent and an input holds the listeners
 * it has installed.
 */
public final class Backend implements ServerBackend {

    private final NmsMapTransport transport = new NmsMapTransport();
    private final NmsPacketInput input = new NmsPacketInput();
    private final NmsRotationController rotation = new NmsRotationController();
    private final NmsSavedMapPixels savedMapPixels = new NmsSavedMapPixels();

    @Override
    public MapTransport transport() {
        return transport;
    }

    @Override
    public PacketInput input() {
        return input;
    }

    @Override
    public RotationController rotation() {
        return rotation;
    }

    @Override
    public SavedMapPixels savedMapPixels() {
        return savedMapPixels;
    }
}
