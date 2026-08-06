package de.flog99.mapgui.plugin.camera;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.profile.PlayerProfile;
import de.flog99.mapgui.render.SkinLayers;
import de.flog99.mapgui.render.Texture;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player skins, fetched once each and kept.
 *
 * <p>Skins are the one part of a capture that needs no installed textures at all: they come from the profile's
 * own URL on Mojang's texture host rather than from any file on the server, so a camera draws people correctly
 * even before the block textures have been downloaded.
 *
 * <p>Keyed by URL rather than by player, so changing a skin fetches the new one and two people wearing the same
 * skin share it.
 */
final class SkinCache {

    /** Names handed to {@link de.flog99.mapgui.render.Textures}, which no asset pack can collide with. */
    private static final String PREFIX = "mapgui:skin/";

    private final Map<String, Texture> skins = new ConcurrentHashMap<>();
    private final Map<String, Boolean> fetching = new ConcurrentHashMap<>();

    /**
     * The texture name for a player, or null when their skin is not here yet.
     *
     * <p>Never blocks and never fetches on the calling thread: a missing skin comes back null and the caller
     * falls back, while the download happens behind it and the next capture has it.
     */
    String nameFor(Player player) {
        URL url = skinUrl(player);
        if (url == null) return null;

        String name = PREFIX + Integer.toHexString(url.toString().hashCode());
        if (skins.containsKey(name)) return name;

        if (fetching.putIfAbsent(name, Boolean.TRUE) == null) {
            Thread.ofVirtual().name("mapgui-skin").start(() -> load(name, url));
        }
        return null;
    }

    /** Whether this player's arms are the narrow ones, which changes the model rather than the texture. */
    boolean isSlim(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        return profile.getTextures().getSkinModel() == org.bukkit.profile.PlayerTextures.SkinModel.SLIM;
    }

    /**
     * Which parts of their second skin layer this player has switched on.
     *
     * <p>Their own setting rather than the camera holder's, because that is what every other client draws them
     * with: the skin parts a client sends are broadcast, and are as much a part of how somebody looks as their
     * skin file is.
     */
    SkinLayers layersOf(Player player) {
        SkinParts parts = player.getClientOption(ClientOption.SKIN_PARTS);
        return new SkinLayers(
                parts.hasHatsEnabled(),
                parts.hasJacketEnabled(),
                parts.hasRightSleeveEnabled(), parts.hasLeftSleeveEnabled(),
                parts.hasRightPantsEnabled(), parts.hasLeftPantsEnabled()
        );
    }

    /** Hands the decoded skins to an atlas so the tracer can look them up by name like any other texture. */
    void publishTo(TextureAtlas atlas) {
        skins.forEach(atlas::put);
    }

    private static URL skinUrl(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        return profile.getTextures().getSkin();
    }

    private void load(String name, URL url) {
        // Through HttpClient rather than ImageIO.read(URL) so it can time out: a stalled connection there hangs
        // forever, and with the name still marked as fetching that player would never be retried.
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                fetching.remove(name);
                return;
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));
            if (image == null) {
                fetching.remove(name);
                return;
            }

            int width = image.getWidth();
            int height = image.getHeight();
            skins.put(name, Texture.opaqueOf(width, height, image.getRGB(0, 0, width, height, null, 0, width)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fetching.remove(name);
        } catch (Exception e) {
            // A skin that will not come down costs that player their face and nothing else, and dropping the
            // marker means the next capture tries again.
            fetching.remove(name);
        }
    }
}
