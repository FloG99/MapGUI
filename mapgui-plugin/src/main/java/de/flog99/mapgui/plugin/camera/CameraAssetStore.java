package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.camera.CameraAssets;
import de.flog99.mapgui.render.AssetCache;
import de.flog99.mapgui.render.AssetResolver;
import de.flog99.mapgui.render.AssetStack;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Owns the camera's textures: what state they are in, when to fetch them, and what to tell whoever can fix it.
 *
 * <p>Nothing is fetched at startup. MapGUI.jar on its own does nothing visible, so most installs of it are
 * somebody setting up a library and will never take a screenshot - pulling 39 MB from an external host for a
 * feature nobody has called is the kind of thing that gets a plugin distrusted, and it fails on hosts with no
 * outbound route. The fetch happens on the first capture instead, because that is somebody actually asking
 * for the feature.
 *
 * <p>Logging is per state change rather than per attempt. A camera renders repeatedly, so anything that logs
 * on the render path buries the one line that mattered under a hundred thousand copies of it.
 */
public final class CameraAssetStore {

    private final Plugin plugin;
    private final Path assetsDir;
    private final Path cacheDir;

    /** Guards against a second fetch starting while the first is still running. */
    private final AtomicBoolean fetching = new AtomicBoolean();

    /** What {@link #reportDamage()} has already said, so a broken layer is not announced once per capture. */
    private final Set<String> reportedDamage = ConcurrentHashMap.newKeySet();

    private volatile CameraAssets state;
    private volatile AssetStack stack;

    private List<String> packNames;
    private boolean downloadEnabled;
    private boolean allowVersionMismatch;

    /**
     * The packs the server was seen handing its players, asked for at load rather than held.
     *
     * <p>A supplier because the two point at each other: what finds those packs needs this to reload once it has
     * one, and this needs the list every time it loads. Nothing is set up before the other exists this way.
     */
    private Supplier<List<Path>> followedPacks = List::of;

    public CameraAssetStore(Plugin plugin, List<String> packNames, boolean downloadEnabled, boolean allowVersionMismatch) {
        this.plugin = plugin;
        this.assetsDir = plugin.getDataFolder().toPath().resolve("assets");
        this.cacheDir = plugin.getDataFolder().toPath().resolve("cache").resolve("camera");
        this.packNames = List.copyOf(packNames);
        this.downloadEnabled = downloadEnabled;
        this.allowVersionMismatch = allowVersionMismatch;
        // Replaced by announce() during onEnable, so nothing observes this.
        this.state = new CameraAssets.Unavailable(CameraAssets.Cause.NOT_INSTALLED, "Camera textures have not been checked yet.", "They are read on the first capture.");
    }

    /**
     * Reads what is on disk and says what will happen about it. Called once, at enable.
     *
     * <p>Reading is not fetching, and doing it now is what lets the first capture answer before
     * anybody has taken a capture. The download still waits for something to ask for one.
     */
    public synchronized void announce() {
        load(false);

        if (downloadEnabled && state instanceof CameraAssets.Unavailable unavailable && unavailable.cause() == CameraAssets.Cause.NOT_INSTALLED) {
            plugin.getLogger().info("Camera textures are not installed. They will download from Mojang the first time something takes a capture.");
            plugin.getLogger().info("To turn that off, set camera.assets.download to false in config.yml.");
        }
    }

    /** Where to ask for the packs the server hands its players. Set once, at enable. */
    public void follow(Supplier<List<Path>> packs) {
        this.followedPacks = packs;
    }

    /**
     * Says once that a layer has stopped being readable, and what it was.
     *
     * <p>Nothing else would say it. A pack that goes bad under an open handle is read as a pack that simply does
     * not have the file, so a capture keeps working and quietly draws from the wrong layer - a plugin's own items
     * come out as their base material with no message anywhere. Called after a capture rather than at load,
     * because that is when it happens: the usual cause is another plugin installing its pack over the top of the
     * one this already has open.
     *
     * <p>Once per pack. Every capture after the first would say the same thing.
     */
    public void reportDamage() {
        AssetStack loaded = stack;
        if (loaded == null) return;

        for (String hurt : loaded.damage()) {
            if (reportedDamage.add(hurt)) {
                plugin.getLogger().warning("A camera asset layer has stopped being readable: " + hurt);
                plugin.getLogger().warning("Anything it was drawing now falls back to the layer underneath. This is what a pack replaced while the server had it open looks like - restart to pick up the new one.");
            }
        }
    }

    /**
     * What the camera can do right now. Cheap enough to call per frame.
     *
     * <p>A screen should check this before drawing rather than after: greying out its own button reads better
     * than any error frame can.
     */
    public CameraAssets state() {
        return state;
    }

    /**
     * The loaded layers, or null when {@link #state()} is not {@link CameraAssets.Ready}.
     *
     * <p>Held rather than handed out per call because opening the zips again per frame would be absurd, and
     * every reader of it is on the render path.
     */
    public AssetStack stack() {
        return stack;
    }

    /**
     * Loads what is on disk, and starts a download if that is what is missing and it is allowed.
     *
     * <p>Called on the first capture and by {@code /mapgui camera reload}. Safe to call repeatedly: it does
     * nothing once loaded, and will not start a second download over the top of a running one.
     */
    public synchronized void ensure() {
        if (state instanceof CameraAssets.Ready || state instanceof CameraAssets.Loading) {
            return;
        }

        load(true);
    }

    /** Drops what is loaded and works it out again from scratch. */
    public synchronized void reload() {
        closeStack();
        load(true);
    }

    /** Picks up a config change without re-reading the disk unless something that matters moved. */
    public synchronized void retune(List<String> packNames, boolean downloadEnabled, boolean allowVersionMismatch) {
        boolean changed = !this.packNames.equals(packNames)
                || this.downloadEnabled != downloadEnabled
                || this.allowVersionMismatch != allowVersionMismatch;

        this.packNames = List.copyOf(packNames);
        this.downloadEnabled = downloadEnabled;
        this.allowVersionMismatch = allowVersionMismatch;

        if (changed) {
            reload();
        }
    }

    /**
     * Downloads now rather than on first use, for an admin who would rather not wait for it later.
     *
     * @return false if a download is already running or the config forbids one
     */
    public synchronized boolean fetchNow() {
        if (!downloadEnabled) return false;

        return startFetch(Bukkit.getMinecraftVersion(), null);
    }

    public synchronized void close() {
        closeStack();
    }

    /**
     * @param mayFetch whether being short of what it needs is allowed to start a download. False at enable,
     *                 where the point is to find out what is there rather than to go and get it
     */
    private void load(boolean mayFetch) {
        String wanted = Bukkit.getMinecraftVersion();
        AssetResolver.Request request = new AssetResolver.Request(assetsDir, cacheDir, packNames, followedPacks.get(), wanted, allowVersionMismatch);

        switch (AssetResolver.resolve(request)) {
            case AssetResolver.Resolution.Loaded loaded -> adopt(loaded);
            case AssetResolver.Resolution.Missing missing -> onMissing(missing, mayFetch);
            case AssetResolver.Resolution.Mismatched mismatched -> onMismatch(mismatched, mayFetch);
            case AssetResolver.Resolution.Broken broken -> unavailable(CameraAssets.Cause.UNREADABLE, broken.detail(), broken.fix(), true);
        }
    }

    /**
     * Nothing on disk. With downloading on this is a normal fresh install and fixes itself, so it is reported
     * as a plain fact rather than as a fault - {@link #announce} is what tells an admin it is going to happen.
     */
    private void onMissing(AssetResolver.Resolution.Missing missing, boolean mayFetch) {
        if (mayFetch && startFetch(missing.wantedVersion(), null)) {
            return;
        }

        if (downloadEnabled) {
            state = new CameraAssets.Unavailable(
                    CameraAssets.Cause.NOT_INSTALLED,
                    "Camera textures for Minecraft " + missing.wantedVersion() + " are not installed",
                    "They download on the first capture."
            );
            return;
        }

        unavailable(
                CameraAssets.Cause.DOWNLOAD_DISABLED,
                "Camera textures are not installed and camera.assets.download is false, so MapGUI will not fetch them",
                "Set camera.assets.download: true in config.yml, or put a client jar or resource pack zip in plugins/MapGUI/assets/ and list it under camera.assets.packs. See docs/camera.md.",
                true
        );
    }

    private void adopt(AssetResolver.Resolution.Loaded loaded) {
        closeStack();
        stack = loaded.stack();
        state = new CameraAssets.Ready(loaded.stack().version(), loaded.stack().blockTextureCount());

        // Counted rather than named. Every layer past the base is a SHA-1, so naming them spent a console screen
        // to say a number, twice per startup, and the one reader who wants to know which packs made it in can ask
        // for them with 'mapgui camera'.
        int overlays = loaded.stack().layerNames().size() - 1;
        plugin.getLogger().info("Camera assets ready: Minecraft " + loaded.stack().version() + ", "
                + loaded.stack().blockTextureCount() + " block textures, " + loaded.stack().entityMeshCount()
                + " mob shapes" + (overlays > 0 ? ", " + overlays + " pack" + (overlays == 1 ? "" : "s") + " over vanilla" : "") + ".");

        if (loaded.stack().entityMeshCount() == 0) {
            // Not a fault. Mob geometry is baked out of a client jar, so a base that is only a resource pack has
            // none to give, and a jar whose libraries do not match this server's cannot be run for it.
            plugin.getLogger().info("No mob shapes came with those assets, so mobs will be drawn as bounding boxes. Everything else is unaffected. See docs/camera.md.");
        }

        if (loaded.mismatchAllowed()) {
            plugin.getLogger().warning("Those assets are for Minecraft " + loaded.stack().version() + ", not " + Bukkit.getMinecraftVersion() + ", and camera.assets.allow-version-mismatch is true. Blocks added or renamed since then will render as missing-texture checkerboard.");
        }
    }

    /**
     * The wrong version is usually a server that has just been upgraded, so the common case fixes itself:
     * fetch the right base and put it underneath whatever the admin supplied. Their file is never replaced,
     * which is the difference between a cache we manage and a directory we only read.
     */
    private void onMismatch(AssetResolver.Resolution.Mismatched mismatched, boolean mayFetch) {
        String detail = "Camera assets are for Minecraft " + mismatched.baseVersion() + " but this server is " + mismatched.wantedVersion();

        if (mayFetch && startFetch(mismatched.wantedVersion(), detail + ". Downloading the correct textures from Mojang. This happens once.")) {
            return;
        }

        String fix = mismatched.adminSupplied()
                ? "Replace plugins/MapGUI/assets/" + mismatched.baseName() + " with the Minecraft " + mismatched.wantedVersion() + " client jar, or set camera.assets.download: true and run '/mapgui camera reload'."
                : "Set camera.assets.download: true in config.yml and run '/mapgui camera reload'.";

        if (downloadEnabled) {
            // Will right itself on the first capture, so this is a heads-up rather than a fault.
            state = new CameraAssets.Unavailable(CameraAssets.Cause.VERSION_MISMATCH, detail, "The correct textures download on the first capture.");
            plugin.getLogger().warning(detail + ". The correct ones will be downloaded on the first capture.");
            return;
        }

        unavailable(CameraAssets.Cause.VERSION_MISMATCH, detail + ", and camera.assets.download is false so MapGUI will not fetch the right ones", fix, true);
    }

    /**
     * @param announcement logged before the download starts, or null to stay quiet about it
     * @return whether a download actually started
     */
    private boolean startFetch(String minecraftVersion, String announcement) {
        if (!downloadEnabled || !fetching.compareAndSet(false, true)) {
            return false;
        }

        state = new CameraAssets.Loading(0);
        if (announcement != null) {
            plugin.getLogger().info(announcement);
        } else {
            plugin.getLogger().info("Downloading camera textures for Minecraft " + minecraftVersion + " from Mojang. This is the client jar, it happens once, and only the textures are kept.");
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                new AssetCache(cacheDir).fetch(minecraftVersion, percent -> state = new CameraAssets.Loading(percent));
                // Back on the main thread: reading the zips settles the state, and every reader of it is
                // either a command or a render, both of which are there.
                Bukkit.getScheduler().runTask(plugin, this::reload);
            } catch (IOException e) {
                state = new CameraAssets.Unavailable(
                        CameraAssets.Cause.DOWNLOAD_FAILED,
                        "Could not download camera textures from Mojang: " + e.getMessage(),
                        "If this server has no outbound internet access, copy the Minecraft " + minecraftVersion + " client jar into plugins/MapGUI/assets/ and list it under camera.assets.packs, then run '/mapgui camera reload'. See docs/camera.md."
                );
                plugin.getLogger().warning("Could not download camera textures from Mojang: " + e.getMessage());
                plugin.getLogger().warning("The camera is disabled until this is fixed. If this server has no outbound internet access, copy the Minecraft " + minecraftVersion + " client jar into plugins/MapGUI/assets/ and list it under camera.assets.packs, then run '/mapgui camera reload'.");
            } finally {
                fetching.set(false);
            }
        });

        return true;
    }

    private void unavailable(CameraAssets.Cause cause, String detail, String fix, boolean warn) {
        state = new CameraAssets.Unavailable(cause, detail, fix);
        if (!warn) return;

        plugin.getLogger().warning(detail + ".");
        plugin.getLogger().warning("The camera is disabled until this is fixed. " + fix);
    }

    private void closeStack() {
        AssetStack open = stack;
        if (open != null) {
            open.close();
            stack = null;
        }
    }
}
