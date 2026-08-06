package de.flog99.mapgui.map;

/**
 * Writes a picture into the pixels the world itself saves for a map.
 *
 * <p>Which is the whole trick behind {@link MapPrinter}. Vanilla keeps one byte array per map in the world's own
 * saved data and writes it to {@code map_N.dat}; the ordinary map renderer paints that array and nothing else. So a
 * picture put in there is a vanilla map that happens to show a photograph - it survives a restart, and it survives
 * MapGUI being uninstalled, because MapGUI is not what draws it.
 *
 * <p>Needs the server internals, so a fork that has moved them can fail to write. That is a return value rather than
 * an exception: the caller falls back to drawing the picture itself, which still shows but does not outlive the
 * plugin.
 */
public interface SavedMapPixels {

    /**
     * Puts one map's worth of palette indices into the map's saved pixels and locks it, so nothing scans terrain
     * over the picture afterwards.
     *
     * @return false if this server does not let us at them, in which case nothing was written
     */
    boolean write(int mapId, byte[] pixels);
}
