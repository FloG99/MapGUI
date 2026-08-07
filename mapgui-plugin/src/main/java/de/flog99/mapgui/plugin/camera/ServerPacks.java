package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.ServerPackCache;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Packs to draw captures with beyond vanilla, from the two places one can come from.
 *
 * <p>A capture is meant to be what a player sees, and on a server with a resource pack that is not vanilla.
 * Making an admin install the same pack twice - once for their players and once here - is asking them to keep
 * two copies in step forever, and the copy that goes stale is the one nothing complains about.
 *
 * <ul>
 *   <li><b>The one in {@code server.properties}</b>, which the API hands over on request. Found on its own, with
 *       nothing to configure.
 *   <li><b>One a plugin hands over</b>, through {@link de.flog99.mapgui.camera.Camera#useResourcePack}. This is
 *       the route for a plugin that adds items and serves its own pack for them.
 * </ul>
 *
 * <p>A pack a plugin pushes to players <i>without</i> telling MapGUI cannot be found. It was tried: nothing
 * reports one - {@code PlayerResourcePackStatusEvent} carries a pack's id and hash but not its URL, and a URL is
 * what a fetch needs - so the only place the address exists is the outgoing packet, and reading it there did not
 * work. What is left of that attempt is the warning below, which turns a silently wrong-looking capture into a
 * line naming the two routes above.
 *
 * <p>Whatever is found is kept under the SHA-1 of its own bytes and layered under anything the admin put in
 * {@code assets/} - explicit beats detected. Content-addressed means the same pack costs one write ever, and a
 * changed one replaces itself.
 */
public final class ServerPacks implements Listener {

    private final Plugin plugin;
    private final CameraAssetStore assets;
    private final ServerPackCache cache;

    /** URLs already dealt with, so nothing is fetched twice. */
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** One fetch at a time, since the second would be for the same pack the first is already getting. */
    private final AtomicBoolean fetching = new AtomicBoolean();

    /** Said once. A server whose pack cannot be found would otherwise say so on every join. */
    private final AtomicBoolean warnedUnfollowable = new AtomicBoolean();

    private volatile boolean enabled;

    public ServerPacks(Plugin plugin, CameraAssetStore assets, Path cacheDir, boolean enabled) {
        this.plugin = plugin;
        this.assets = assets;
        this.cache = new ServerPackCache(cacheDir);
        this.enabled = enabled;
    }

    /**
     * What has been kept, for the resolver to layer.
     *
     * <p>A pack a plugin handed over stays in use even with following off: that switch is about MapGUI going
     * looking for things, and a plugin asking directly is not MapGUI going looking.
     */
    public List<Path> followed() {
        return cache.stored();
    }

    /**
     * Turned on by a config reload means go and look now, rather than at the next restart.
     *
     * <p>Turning it off stops the looking and leaves what was already found in place - the switch is about MapGUI
     * reaching out, and a pack already on disk is not reaching out. Delete it from
     * {@code cache/camera/packs/} to be rid of it.
     */
    public void retune(boolean nowEnabled) {
        boolean wasOff = !enabled;
        this.enabled = nowEnabled;

        if (nowEnabled && wasOff) {
            offer(Bukkit.getResourcePack(), Bukkit.getResourcePackHash());
        }
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);

        if (enabled) {
            offer(Bukkit.getResourcePack(), Bukkit.getResourcePackHash());
        }
    }

    /**
     * A pack out of a plugin's own jar. The API call behind {@code Camera#useResourcePack}.
     *
     * <p>Synchronous and small: reading a few hundred KB out of an already-open jar and hashing it is not worth a
     * thread, and doing it inline means a plugin that calls this from its own {@code onEnable} has the pack in
     * place before anything has had the chance to take a capture.
     */
    public void use(Plugin owner, String resource) {
        try (InputStream stream = owner.getResource(resource)) {
            if (stream == null) {
                plugin.getLogger().warning(owner.getName() + " asked for captures to be drawn with " + resource
                        + ", but there is no such file in its jar.");
                return;
            }

            ServerPackCache.Stored stored = cache.keep(stream.readAllBytes());
            if (!stored.fresh()) return;

            plugin.getLogger().info("Captures will be drawn with " + owner.getName() + "'s resource pack ("
                    + stored.zip().getFileName() + ").");
            assets.reload();
        } catch (Exception failure) {
            plugin.getLogger().log(Level.WARNING, "Could not take " + resource + " from " + owner.getName()
                    + ", so captures will draw its items from their base materials instead.", failure);
        }
    }

    /**
     * A pack reached a player that MapGUI is not drawing with, which is worth one line.
     *
     * <p>Only when nothing at all has been layered - a server that has sorted this out by either route is not
     * told about it. What this catches is the case that otherwise looks like nothing happening: captures coming
     * out in vanilla textures while every player is looking at something else.
     */
    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        if (!layeredNothing() || !warnedUnfollowable.compareAndSet(false, true)) return;

        plugin.getLogger().warning("Players are being sent a resource pack and captures are being drawn without it,"
                + " so a photograph will not look like what the people in it can see.");
        plugin.getLogger().warning("A pack pushed by a plugin cannot be found from here - only its id and hash are"
                + " reported, never its address. Put a copy in plugins/MapGUI/assets/, or have the plugin call"
                + " Camera#useResourcePack. See docs/camera.md.");
    }

    /** Whether the stack is vanilla and nothing else, which is the only state that warning is about. */
    private boolean layeredNothing() {
        return assets.stack() != null && assets.stack().layerNames().size() <= 1;
    }

    /** A pack the server itself is configured to hand out. */
    private void offer(String url, String rawHash) {
        if (url == null || url.isBlank()) return;

        // server.properties leaves this null when nobody filled resource-pack-sha1 in, and only one of null and
        // empty survives being read.
        String hash = rawHash == null ? "" : rawHash;
        if (!seen.add(url)) return;
        if (cache.has(hash)) return;
        if (!fetching.compareAndSet(false, true)) {
            seen.remove(url);
            return;
        }

        Bukkit.getAsyncScheduler().runNow(plugin, task -> fetch(url, hash));
    }

    /** Off the main thread. A pack is megabytes over somebody else's HTTP server. */
    private void fetch(String url, String hash) {
        try {
            Path stored = cache.fetch(url, hash);
            plugin.getLogger().info("Fetched the resource pack this server sends its players, so captures are drawn"
                    + " with it: " + stored.getFileName() + ".");

            // Back on the main thread: a reload closes and reopens every zip, which is not a thing to do
            // underneath a capture that is already tracing.
            Bukkit.getScheduler().runTask(plugin, assets::reload);
        } catch (Exception failure) {
            // Not a warning. The server's pack being unreachable from the server is ordinary - it is an address
            // written for clients - and captures fall back to vanilla, which is what they did before.
            plugin.getLogger().log(Level.INFO, "Could not follow the server's resource pack, so captures use vanilla"
                    + " textures instead: " + failure.getMessage());
            seen.remove(url);
        } finally {
            fetching.set(false);
        }
    }
}
