package de.flog99.mapgui.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which texture each of a mob's coats wears, by asking the client's own renderer.
 *
 * <p>Most variants became registry entries and {@link EntityVariants} reads those. The ones that did not are held in
 * the client as code and nothing in the assets states them - a parrot's five textures are five identifiers inside
 * {@code ParrotRenderer}, a shulker's sixteen are a switch over the dye colours. Guessing from the base name does not
 * merely fail there, it succeeds wrongly: a parrot's base is {@code parrot_red_blue}, so swapping the last word turns
 * the blue parrot back into the red one.
 *
 * <p>So the renderer is asked instead, the same way {@link MeshExtractor} runs the client's mesh builders rather than
 * transcribing them. The method wanted is any static one taking a single enum and handing back an identifier, called
 * once per constant. Nothing here knows what a parrot is.
 *
 * <p>This works on a server because the renderer classes only need Minecraft's own shared libraries, joml among them,
 * and those are on a server already - it is the client's rendering it cannot do, not the reading of its tables.
 */
final class RendererCoats {

    static final String FILE = "mapgui-coats.json";

    /** Where a renderer lives, and the suffix its class carries. Convention, not a table - a miss just skips. */
    private static final String RENDERERS = "net.minecraft.client.renderer.entity.";

    private static final String RENDERER = "Renderer";

    /** What the client returns, so a texture can be told from anything else a renderer hands out. */
    private static final String IDENTIFIER = "Identifier";

    /**
     * One coat, in the client's own order.
     *
     * @param variant what the client calls it, lowercased - which is not always what the server calls it, so the
     *                position matters as much as the name
     */
    record Coat(String variant, String texture) {
    }

    private RendererCoats() {
    }

    static Map<String, List<Coat>> extract(Path jar, ClassLoader parent, Collection<String> types) throws IOException {
        URL[] classpath = {jar.toUri().toURL()};

        try (URLClassLoader loader = new ClientLoader("mapgui-client-coats", classpath, parent)) {
            Map<String, List<Coat>> coats = new LinkedHashMap<>();
            for (String type : types) {
                List<Coat> found = of(loader, type);
                if (!found.isEmpty()) {
                    coats.put(type, found);
                }
            }
            return coats;
        }
    }

    /** Empty for a mob whose renderer has no such table, which is most of them. */
    private static List<Coat> of(ClassLoader loader, String type) {
        Class<?> renderer;
        try {
            renderer = Class.forName(RENDERERS + camelCase(type) + RENDERER, true, loader);
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            // Named after something other than its entity, or not a mob this version has. Neither is a problem.
            return List.of();
        }

        for (Method method : declared(renderer)) {
            List<Coat> coats = coats(method);
            if (!coats.isEmpty()) return coats;
        }
        return List.of();
    }

    private static Method[] declared(Class<?> renderer) {
        try {
            return renderer.getDeclaredMethods();
        } catch (RuntimeException | LinkageError e) {
            return new Method[0];
        }
    }

    private static List<Coat> coats(Method method) {
        if (!Modifier.isStatic(method.getModifiers())) return List.of();
        if (!method.getReturnType().getSimpleName().equals(IDENTIFIER)) return List.of();
        if (method.getParameterCount() != 1) return List.of();

        Class<?> variants = method.getParameterTypes()[0];
        if (!variants.isEnum()) return List.of();

        List<Coat> coats = new ArrayList<>();
        try {
            method.setAccessible(true);
            for (Object constant : variants.getEnumConstants()) {
                Object texture = method.invoke(null, constant);
                if (texture == null) continue;

                String path = unqualified(texture.toString());
                if (path != null) {
                    coats.add(new Coat(((Enum<?>) constant).name().toLowerCase(Locale.ROOT), path));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // A renderer that will not answer leaves its mob on the name rule, which is what it had before.
            return List.of();
        }
        return coats;
    }

    /** {@code minecraft:textures/entity/parrot/parrot_blue.png} to the {@code entity/...} an atlas is keyed by. */
    private static String unqualified(String identifier) {
        int namespace = identifier.indexOf(':');
        String path = namespace < 0 ? identifier : identifier.substring(namespace + 1);

        if (!path.startsWith("textures/") || !path.endsWith(".png")) return null;

        return path.substring("textures/".length(), path.length() - ".png".length());
    }

    /** {@code sulfur_cube} to {@code SulfurCube}, which is how the client spells a renderer. */
    private static String camelCase(String type) {
        StringBuilder out = new StringBuilder(type.length());
        for (String word : type.split("_")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
        }
        return out.toString();
    }

    static byte[] write(Map<String, List<Coat>> coats) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, List<Coat>> entry : coats.entrySet()) {
            JsonArray array = new JsonArray();
            for (Coat coat : entry.getValue()) {
                JsonObject one = new JsonObject();
                one.addProperty("variant", coat.variant());
                one.addProperty("texture", coat.texture());
                array.add(one);
            }
            json.add(entry.getKey(), array);
        }
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Empty for a pack packed before this file existed, which leaves every mob on the name rule. */
    static Map<String, List<Coat>> read(byte[] raw) {
        if (raw == null) return Map.of();

        try {
            JsonObject json = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, List<Coat>> coats = new LinkedHashMap<>();

            for (String type : json.keySet()) {
                List<Coat> read = new ArrayList<>();
                for (var element : json.getAsJsonArray(type)) {
                    JsonObject one = element.getAsJsonObject();
                    read.add(new Coat(one.get("variant").getAsString(), one.get("texture").getAsString()));
                }
                coats.put(type, List.copyOf(read));
            }
            return Map.copyOf(coats);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
