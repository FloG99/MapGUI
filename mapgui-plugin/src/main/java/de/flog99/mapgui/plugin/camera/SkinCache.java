package de.flog99.mapgui.plugin.camera;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.SkinParts;
import com.destroystokyo.paper.profile.PlayerProfile;
import de.flog99.mapgui.render.EntitySnapshot;
import de.flog99.mapgui.render.SkinLayers;
import de.flog99.mapgui.render.Texture;
import de.flog99.mapgui.render.TextureAtlas;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
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
        return nameFor(player.getPlayerProfile());
    }

    /**
     * The same for anybody who is not here to be asked - the owner of a placed head, or of one lying on the floor.
     *
     * <p>Null for a profile with no skin on it as well as for one whose skin has not come down yet, which is the
     * ordinary case for a head placed from a hand: the server resolves the owner in the background and the profile
     * carries a name and nothing else until it does.
     */
    String nameFor(PlayerProfile profile) {
        URL url = profile == null ? null : profile.getTextures().getSkin();
        if (url == null) return null;

        String name = PREFIX + Integer.toHexString(url.toString().hashCode());
        if (skins.containsKey(name)) return name;

        if (fetching.putIfAbsent(name, Boolean.TRUE) == null) {
            Thread.ofVirtual().name("mapgui-skin").start(() -> load(name, url));
        }
        return null;
    }

    /**
     * The same layers wearing the owner's face, for the one item whose texture is not the item's.
     *
     * <p>A player head is drawn from the skull mesh in whichever skin the stack names, and
     * {@link de.flog99.mapgui.render.ItemModels} can only know the default one - whose face it is belongs to the
     * stack. Anything that is not a head comes back untouched, and so does a head whose owner is unresolved or whose
     * skin has not come down yet: vanilla's own default face beats no head at all.
     */
    List<EntitySnapshot> faced(List<EntitySnapshot> layers, ItemStack item) {
        if (item.getType() != Material.PLAYER_HEAD || !(item.getItemMeta() instanceof SkullMeta head)) return layers;

        String skin = nameFor(head.getPlayerProfile());
        return skin == null ? layers : layers.stream().map(layer -> layer.texture(skin)).toList();
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
