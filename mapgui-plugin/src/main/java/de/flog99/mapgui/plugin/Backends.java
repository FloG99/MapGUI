package de.flog99.mapgui.plugin;

import de.flog99.mapgui.ServerBackend;
import org.bukkit.Bukkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Picks the {@link ServerBackend} built for the server it finds itself on.
 *
 * <p>By name rather than by import, because each backend is compiled against its own server jar: naming one in
 * code would drag every version's classes onto the classpath at once, and only one of them can be loaded.
 *
 * <p>Matched on the family - the first two parts of the version - so a patch release runs on the module built
 * for the release it patches. Patches change the odd class name and almost never the packet shapes, and the
 * alternative is a module per patch that would be a copy of the one before it.
 */
final class Backends {

    /**
     * Newest first, and the whole of what MapGUI knows about server internals.
     *
     * <p>A new Minecraft version is a module, an entry here, and a line in the plugin's build file.
     */
    private static final Map<String, String> BY_FAMILY = new LinkedHashMap<>();

    static {
        BY_FAMILY.put("26.2", "de.flog99.mapgui.nms.v26_2.Backend");
    }

    private Backends() {
    }

    /**
     * @throws IllegalStateException if this server's version has no backend, which is the one thing MapGUI
     *         cannot work around and so is worth failing loudly on
     */
    static ServerBackend forThisServer() {
        String version = Bukkit.getMinecraftVersion();
        String name = BY_FAMILY.get(family(version));

        if (name == null) {
            throw new IllegalStateException("MapGUI has no support for Minecraft " + version
                    + ". It knows " + String.join(", ", BY_FAMILY.keySet())
                    + ", so this is a build too old for the server rather than anything misconfigured."
            );
        }

        try {
            return (ServerBackend) Class.forName(name).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("MapGUI's support for Minecraft " + version
                    + " would not load. This is what a server internals change looks like, so a newer MapGUI is"
                    + " the fix.", e
            );
        }
    }

    /** {@code 26.2.1} and {@code 26.2} are both the 26.2 family; anything shorter is its own. */
    static String family(String version) {
        int major = version.indexOf('.');
        if (major < 0) return version;

        int minor = version.indexOf('.', major + 1);
        return minor < 0 ? version : version.substring(0, minor);
    }
}
