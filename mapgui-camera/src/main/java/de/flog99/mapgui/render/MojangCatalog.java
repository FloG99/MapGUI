package de.flog99.mapgui.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Where Mojang keeps the client jar for a given version.
 *
 * <p>Two unauthenticated documents, in this order: a manifest of every version, then that version's own
 * json, which carries the jar's URL along with its SHA-1 and size. Nothing here needs a token, an account or
 * a launcher.
 *
 * <p>The jar is the target because the asset index is not. That index - and
 * {@code resources.download.minecraft.net} behind it - holds sounds, languages and panoramas; block
 * textures, models and blockstates live inside {@code client.jar} itself, so there is no smaller thing to
 * ask for. 39 MB comes down once and {@link AssetRepack} keeps about 2.9 MB of it.
 */
record MojangCatalog(String version, URI clientJar, String sha1, long size) {

    private static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");

    /** Long enough to survive a slow host, short enough that a blocked egress route fails rather than hangs. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * Resolves the jar for one exact version.
     *
     * @throws IOException if either document cannot be read, or if the manifest has never heard of this
     *                     version - which is what a snapshot server or a fork with its own version string
     *                     looks like from here, and is not something a retry fixes
     */
    static MojangCatalog resolve(String minecraftVersion) throws IOException {
        JsonObject manifest = fetchJson(MANIFEST);
        JsonArray versions = manifest.getAsJsonArray("versions");

        for (JsonElement element : versions) {
            JsonObject entry = element.getAsJsonObject();
            if (!minecraftVersion.equals(entry.get("id").getAsString())) {
                continue;
            }

            JsonObject client = fetchJson(URI.create(entry.get("url").getAsString()))
                    .getAsJsonObject("downloads")
                    .getAsJsonObject("client");

            return new MojangCatalog(
                    minecraftVersion,
                    URI.create(client.get("url").getAsString()),
                    client.get("sha1").getAsString(),
                    client.get("size").getAsLong()
            );
        }

        throw new IOException("Mojang's version manifest has no entry for Minecraft " + minecraftVersion);
    }

    private static JsonObject fetchJson(URI uri) throws IOException {
        try (HttpClient http = client()) {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + uri.getHost());
            }

            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading " + uri.getHost(), e);
        } catch (RuntimeException e) {
            // Gson throws unchecked for a body that is not the json we expect, which from here is the same
            // kind of problem as the request failing.
            throw new IOException("Could not make sense of " + uri.getHost() + ": " + e.getMessage(), e);
        }
    }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Mojang's meta host redirects to its CDN, and NEVER would fail the download for it.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
