package de.flog99.mapgui.render;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Works out what a camera has to draw with, from what is on disk.
 *
 * <p>Deliberately decides and does not act: it will not download, log or schedule anything. The caller owns
 * those, which is what lets every branch here be tested with a temp directory and no server.
 *
 * <p>Two directories, with different rules. {@code plugins/MapGUI/assets/} is the admin's and is only ever
 * read - a file somebody put there may be pinned deliberately, so a version mismatch is reported rather than
 * quietly replaced. {@code plugins/MapGUI/cache/camera/} is ours, and a base missing from it is just a
 * download away.
 */
public final class AssetResolver {

    /**
     * @param packNames           files in {@code assetsDir}, highest priority first, as named in config.yml
     * @param followedPacks       packs the server was seen handing its players, already fetched. Below anything
     *                            an admin put in {@code assetsDir}, because one of those is a decision and these
     *                            are a convenience, and never candidates for the base
     * @param allowVersionMismatch an override for snapshot servers and forks, where the right assets may not
     *                            exist to download. Off by default, because wrong textures are worse than none
     */
    public record Request(
            Path assetsDir,
            Path cacheDir,
            List<String> packNames,
            List<Path> followedPacks,
            String minecraftVersion,
            boolean allowVersionMismatch) {
    }

    /** What the caller should do next. */
    public sealed interface Resolution {

        /** Ready to render. The caller owns the stack and must close it. */
        record Loaded(AssetStack stack, boolean mismatchAllowed) implements Resolution {
        }

        /** No usable base anywhere. A download fixes this, if the caller is allowed to. */
        record Missing(String wantedVersion) implements Resolution {
        }

        /**
         * A base exists and is for the wrong version. A download fixes it, and when the base came from
         * {@code assets/} the fetched one goes underneath rather than over the top of the admin's file.
         */
        record Mismatched(String baseName, String baseVersion, String wantedVersion, boolean adminSupplied) implements Resolution {
        }

        /** Something only a person can fix. */
        record Broken(String detail, String fix) implements Resolution {
        }
    }

    private AssetResolver() {
    }

    /**
     * What to layer, which is the configured list or - when there is none - whatever is sitting in the assets
     * directory.
     *
     * <p>A server that ships a resource pack has one job to get a capture that matches what its players see, and
     * asking it to both drop the file in and name it again in config is one job too many. So an empty
     * {@code camera.assets.packs} means "use what is there" rather than "use nothing", and naming any file at all
     * goes back to exactly that list - a server that wants a particular order still says so, and says it once.
     *
     * <p>Sorted by name, because layering is order-dependent and directory order is not something to build a look
     * on. A file that turns out to be the vanilla base is sorted out later by {@code isComplete}, so a client jar
     * and a pack in the same directory still land the right way up whatever they are called.
     */
    private static List<String> packsToOpen(Request request) {
        if (!request.packNames().isEmpty()) return request.packNames();

        try (Stream<Path> found = Files.list(request.assetsDir())) {
            return found.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".zip") || name.endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException absent) {
            // No directory yet, which is the ordinary state of a server that has never put anything there.
            return List.of();
        }
    }

    public static Resolution resolve(Request request) {
        List<AssetPack> opened = new ArrayList<>();
        try {
            for (String name : packsToOpen(request)) {
                Path path = request.assetsDir().resolve(name);
                if (!Files.isRegularFile(path)) {
                    return broken(opened,
                            "camera.assets.packs names " + name + " but plugins/MapGUI/assets/" + name + " is not there",
                            "Put the file there, or take it out of camera.assets.packs in config.yml."
                    );
                }

                try {
                    opened.add(AssetPack.open(path));
                } catch (IOException e) {
                    return broken(opened,
                            "plugins/MapGUI/assets/" + name + " is not a readable zip",
                            "A client jar or a resource pack zip is expected, unmodified and not unzipped."
                    );
                }
            }

            // The first complete layer becomes the base and stops being an overlay, so an admin who supplies a
            // client jar alongside their own pack gets the arrangement they meant without saying which is which.
            // Only the admin's files are candidates - looked for before the followed ones are opened, so a
            // total-conversion pack that happens to carry stone and dirt cannot promote itself to the base.
            int baseIndex = -1;
            for (int i = 0; i < opened.size(); i++) {
                if (AssetStack.isComplete(opened.get(i))) {
                    baseIndex = i;
                    break;
                }
            }

            for (Path followed : request.followedPacks()) {
                try {
                    opened.add(AssetPack.open(followed));
                } catch (IOException unreadable) {
                    // Ours, fetched rather than supplied, so there is nobody to send after it. The layer is
                    // simply not there, which is the same as the server never having had a pack.
                }
            }

            if (baseIndex < 0) {
                return fromCache(opened, request);
            }

            AssetPack base = opened.get(baseIndex);
            String version = versionOf(base);
            if (version == null) {
                return broken(opened,
                        base.name() + " has textures but no version.json, so there is no way to tell what it is for",
                        "Use an unmodified client jar as the base, or let MapGUI download one."
                );
            }

            if (!version.equals(request.minecraftVersion()) && !request.allowVersionMismatch()) {
                closeAll(opened);
                return new Resolution.Mismatched(base.name(), version, request.minecraftVersion(), true);
            }

            List<AssetPack> overlays = new ArrayList<>(opened);
            overlays.remove(baseIndex);
            return new Resolution.Loaded(AssetStack.of(overlays, base, version), !version.equals(request.minecraftVersion()));
        } catch (RuntimeException e) {
            closeAll(opened);
            throw e;
        }
    }

    /** No admin pack could serve as a base, so the cached download is the only candidate. */
    private static Resolution fromCache(List<AssetPack> overlays, Request request) {
        AssetCache cache = new AssetCache(request.cacheDir());
        String wanted = request.minecraftVersion();

        if (!cache.has(wanted)) {
            // A cached base for some other version is worth reporting as a mismatch rather than as missing:
            // the difference decides whether the message says "install" or "upgrade", and only one of those
            // is the truth after a server update.
            String stale = anyCachedVersion(cache, request.cacheDir());
            if (stale != null && !request.allowVersionMismatch()) {
                closeAll(overlays);
                return new Resolution.Mismatched(stale + ".zip", stale, wanted, false);
            }
            if (stale == null) {
                closeAll(overlays);
                return new Resolution.Missing(wanted);
            }
        }

        String version = cache.has(wanted) ? wanted : anyCachedVersion(cache, request.cacheDir());
        Path zip = cache.zipFor(version);
        AssetPack base;
        try {
            base = AssetPack.open(zip);
        } catch (IOException e) {
            closeAll(overlays);
            // Ours to replace, so this is not something to send an admin after.
            return new Resolution.Missing(wanted);
        }

        if (!AssetStack.isComplete(base)) {
            closeAll(overlays);
            closeQuietly(base);
            return new Resolution.Missing(wanted);
        }

        return new Resolution.Loaded(AssetStack.of(overlays, base, version), !version.equals(wanted));
    }

    /** Newest by file time, matching what {@link AssetCache} prunes by. */
    private static String anyCachedVersion(AssetCache cache, Path cacheDir) {
        if (!Files.isDirectory(cacheDir)) return null;

        try (var files = Files.list(cacheDir)) {
            return files
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".zip"))
                    .map(name -> name.substring(0, name.length() - ".zip".length()))
                    .filter(cache::has)
                    .max((left, right) -> Long.compare(modified(cache.zipFor(left)), modified(cache.zipFor(right))))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The {@code id} out of a jar's version.json, which is the only thing that says what a base is for. */
    private static String versionOf(AssetPack pack) {
        try {
            byte[] raw = pack.read(AssetRepack.VERSION_FILE);
            if (raw == null) return null;

            var json = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has("id") ? json.get("id").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Resolution broken(List<AssetPack> opened, String detail, String fix) {
        closeAll(opened);
        return new Resolution.Broken(detail, fix);
    }

    private static void closeAll(List<AssetPack> packs) {
        for (AssetPack pack : packs) {
            closeQuietly(pack);
        }
    }

    private static void closeQuietly(AssetPack pack) {
        try {
            pack.close();
        } catch (IOException e) {
            // Already on a failure path; there is nothing this could usefully add.
        }
    }
}
