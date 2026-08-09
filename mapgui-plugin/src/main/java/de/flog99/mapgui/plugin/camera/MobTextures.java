package de.flog99.mapgui.plugin.camera;

import de.flog99.mapgui.render.EntityVariants;
import de.flog99.mapgui.render.TextureAtlas;
import de.flog99.mapgui.render.Tints;
import org.bukkit.DyeColor;
import org.bukkit.Keyed;
import org.bukkit.entity.Creaking;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Rabbit;
import org.bukkit.entity.TropicalFish;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.ZombieVillager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which texture an individual mob wears: its own coat, plus whatever vanilla paints over that.
 *
 * <p>Two questions rather than one. A coat is a variant, picked from the game's own registries where they state
 * one; a villager's robe, a horse's markings and a mob's glowing eyes are further passes over the same mesh, which
 * are composited into a single texture because a snapshot samples one.
 */
final class MobTextures {

    /** The badge a villager wears on its belt, by trade level. */
    private static final List<String> BADGES = List.of("stone", "iron", "gold", "emerald", "diamond");

    /**
     * The three rabbit coats whose texture is not named after the coat. A table because there is nothing to read: a
     * rabbit's coats never became registry entries, so the mapping lives in the client as code and no rule over names
     * reaches {@code white_splotched} from {@code black_and_white}. The other four follow the naming.
     */
    private static final Map<String, String> ODD_COATS = Map.of(
            "black_and_white", "white_splotched",
            "salt_and_pepper", "salt",
            "the_killer_bunny", "caerbannog"
    );

    /** What the assets call the Toast rabbit's coat, which is a texture like the other seven and not a special case. */
    private static final String TOAST_COAT = "toast";

    private MobTextures() {
    }

    /**
     * The variant of a mob that has them, as the word the assets name it by, or null for one that does not.
     *
     * <p>By reflection because there is no common supertype: nine mobs each declare their own {@code getVariant}
     * returning their own nested type, and the values are all {@link Keyed}. Bukkit also spells the same idea three
     * ways - {@code getVariant} on the mobs whose variants became registry entries, {@code get<Type>Type} on the ones
     * that predate that, {@code getColor} on a llama - so all three are tried, and the middle is built from the
     * entity id rather than tabulated. Asking only for the first drew every cat as a tabby.
     */
    static String variantOf(Entity entity, String type) {
        // A rabbit called Toast wears a coat of its own, which the assets name after it - so it is a coat like any
        // other from here down, and only the name it is picked by is a joke.
        if (entity instanceof Rabbit && MobNames.named(entity, MobNames.TOAST)) return TOAST_COAT;

        // A tropical fish's variant is its pattern, and the pattern decides which of the two bodies it is drawn on
        // rather than what it looks like - the looks are two colors painted on in {@link #painted}.
        if (entity instanceof TropicalFish fish) {
            return fish.getPattern() == null ? null : body(fish.getPattern());
        }

        // A horse's coat is a colour plus a marking pattern rather than a variant, so the colour is taken on its own.
        if (entity instanceof Horse horse) {
            return horse.getColor() == null ? null : assetWord(horse.getColor().name());
        }

        Object variant = variantValue(entity, type);
        if (variant == null) return null;

        String word = word(variant);
        return word == null ? null : ODD_COATS.getOrDefault(word, word);
    }

    /**
     * Where this mob's variant sits in the server's own enum, or -1 when it does not have one.
     *
     * <p>Only of use to {@link EntityVariants#coatOf}, and only where the two sides disagree on the name: a parrot the
     * server calls {@code cyan} the client calls {@code yellow_blue}, and being the fourth of five is all they share.
     */
    private static int ordinalOf(Entity entity, String type) {
        return variantValue(entity, type) instanceof Enum<?> constant ? constant.ordinal() : -1;
    }

    /** The variant object itself, which the two above read differently. */
    private static Object variantValue(Entity entity, String type) {
        for (String accessor : List.of("getVariant", "get" + camelCase(type) + "Type", "getColor")) {
            Object variant = called(entity, accessor);
            if (variant != null) return variant;
        }
        return null;
    }

    /**
     * The finished texture for one individual, or null when nothing resolves and it is left out of the frame.
     *
     * @param authored what the mesh table names for the species, which is what everything here falls back to
     */
    static String skinOf(Entity entity, String type, String variant, String authored, boolean baby, MobAssets assets) {
        String coat = coatOf(entity, type, variant, authored, baby, assets);
        return coat == null ? null : painted(entity, coat, baby, assets.atlas());
    }

    /**
     * A texture for an entity drawn as its bounding box, or null when the assets hold none for it. Probed rather than
     * tabulated, since entity textures are not named after their entity reliably.
     *
     * <p>Null leaves the entity out of the frame entirely, which is what item frames, paintings, boats and the
     * projectiles get. Both alternatives are worse: an unresolved texture draws as flashing magenta checkerboard, and
     * a stand-in tone draws as a grey block standing where a mob is.
     */
    static String boundingBox(String type, TextureAtlas atlas) {
        for (String candidate : List.of("entity/" + type + "/" + type, "entity/" + type)) {
            if (atlas.has(candidate)) return candidate;
        }

        return null;
    }

    /**
     * The texture a particular animal wears, rather than the one its species does. The registry answers wherever
     * there is one; the rest are probed against the naming the assets follow, which is the base texture's stem with
     * the variant swapped in or appended.
     */
    private static String coatOf(Entity entity, String type, String variant, String authored, boolean baby, MobAssets assets) {
        if (authored == null) return null;

        TextureAtlas atlas = assets.atlas();
        String stated = assets.variants().textureOf(type, variant, baby, moodOf(entity));
        if (stated != null && atlas.has(stated)) return stated;

        // What the client's own renderer says, which is the only source for the coats that never became registry
        // entries. Ahead of the name rule below because that rule does not merely miss on them - a parrot's base is
        // parrot_red_blue, so swapping the last word finds parrot_red_blue again and draws all five as the red one.
        String named = assets.variants().coatOf(type, variant, ordinalOf(entity, type));
        if (named != null && atlas.has(named)) return named;

        for (String candidate : candidates(authored, variant)) {
            if (baby && atlas.has(candidate + "_baby")) return candidate + "_baby";
            if (atlas.has(candidate)) return candidate;
        }
        return authored;
    }

    private static List<String> candidates(String authored, String variant) {
        if (variant == null) return List.of(authored);

        int slash = authored.lastIndexOf('/');
        String stem = authored.substring(slash + 1);
        String directory = authored.substring(0, slash + 1);
        int lastWord = stem.lastIndexOf('_');

        List<String> candidates = new ArrayList<>();
        if (lastWord > 0) {
            candidates.add(directory + stem.substring(0, lastWord) + "_" + variant);
        }
        candidates.add(authored + "_" + variant);
        candidates.add(authored);
        return candidates;
    }

    /**
     * Which of a wolf's three coats it is wearing - the one variant registry that states a set rather than a texture,
     * because a wolf keeps its markings and changes its face.
     */
    private static EntityVariants.Mood moodOf(Entity entity) {
        if (entity instanceof Wolf wolf) {
            if (wolf.isAngry()) return EntityVariants.Mood.ANGRY;
            if (wolf.isTamed()) return EntityVariants.Mood.TAME;
        }

        return EntityVariants.Mood.WILD;
    }

    /**
     * Whatever vanilla paints over a coat, composited in.
     *
     * <p>A villager's texture is a bare body with the biome robe, trade and level badge as further passes; a horse's
     * is a plain coat with its markings painted on. Neither could be picked by name, because both are a
     * <i>combination</i>: seven coats times five markings is seven textures plus four, not thirty-five.
     */
    private static String painted(Entity entity, String base, boolean baby, TextureAtlas atlas) {
        if (entity instanceof Villager villager) {
            return dressed(atlas, base, baby, villager.getVillagerType(), villager.getProfession(), villager.getVillagerLevel());
        }
        if (entity instanceof ZombieVillager zombie) {
            return dressed(atlas, base, baby, zombie.getVillagerType(), zombie.getVillagerProfession(), 1);
        }
        if (entity instanceof Horse horse) {
            return marked(atlas, base, baby, horse.getStyle());
        }
        if (entity instanceof TropicalFish fish) {
            return patterned(atlas, base, fish);
        }

        return eyed(atlas, base, entity);
    }

    /** How many patterns each of the two bodies carries, which is what splits the twelve of them in half. */
    private static final int PATTERNS_PER_BODY = 6;

    /** Which of the two fish bodies a pattern is drawn on: the first six on {@code a}, the second six on {@code b}. */
    private static String body(TropicalFish.Pattern pattern) {
        return pattern.ordinal() < PATTERNS_PER_BODY ? "a" : "b";
    }

    /**
     * A tropical fish, which is two greyscale textures and two dyes rather than one of 3072 pictures: the body in its
     * base color, and one of six patterns over it in its own.
     *
     * <p>Composited rather than drawn as a second layer, because the pattern shares the body's mesh exactly - two
     * snapshots of the same geometry would fight for the pixel instead of stacking.
     */
    private static String patterned(TextureAtlas atlas, String base, TropicalFish fish) {
        TropicalFish.Pattern pattern = fish.getPattern();
        if (pattern == null) return base;

        String over = base + "_pattern_" + (pattern.ordinal() % PATTERNS_PER_BODY + 1);
        if (!atlas.has(over)) return base;

        return atlas.dyed(List.of(
                new TextureAtlas.Dyed(base, Tints.dye(dyeWord(fish.getBodyColor()))),
                new TextureAtlas.Dyed(over, Tints.dye(dyeWord(fish.getPatternColor())))));
    }

    /** A dye as the word {@link Tints} names it by, or one that names nothing - which leaves the layer untinted. */
    private static String dyeWord(DyeColor dye) {
        return dye == null ? "" : dye.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The glowing eyes eight mobs wear over their skin, composited on - a second pass over the same mesh with the
     * same unwrap, which is why compositing gets it right. Each of these textures is the size of the skin it goes
     * over and fully opaque where it draws, and the client blends it {@code TRANSLUCENT} rather than additively.
     *
     * <p>Probed rather than tabulated: named after the skin for most, with the oxidation state after {@code _eyes}
     * for a copper golem, and off the folder's species for a cave spider, which shares the spider's pair.
     *
     * <p><b>Not emissive here.</b> The client draws these fullbright, so a vanilla enderman's eyes glow in the dark
     * and these are lit like the rest of it.
     */
    private static String eyed(TextureAtlas atlas, String base, Entity entity) {
        int slash = base.lastIndexOf('/');
        if (slash < 0) return base;

        // A dormant creaking has no eyes to show, which is the one of these that comes and goes.
        if (entity instanceof Creaking creaking && !creaking.isActive()) return base;

        String directory = base.substring(0, slash + 1);
        String stem = base.substring(slash + 1);
        String folder = base.substring(base.lastIndexOf('/', slash - 1) + 1, slash);
        int lastWord = stem.lastIndexOf('_');

        List<String> candidates = new ArrayList<>();
        candidates.add(base + "_eyes");
        if (lastWord > 0) {
            candidates.add(directory + stem.substring(0, lastWord) + "_eyes" + stem.substring(lastWord));
        }
        candidates.add(directory + folder + "_eyes");

        for (String eyes : candidates) {
            if (atlas.has(eyes)) return atlas.layered(base, List.of(eyes));
        }
        return base;
    }

    /** A horse's markings, which are a texture over its coat rather than part of it. */
    private static String marked(TextureAtlas atlas, String base, boolean baby, Horse.Style style) {
        int slash = base.lastIndexOf('/');
        if (style == null || style == Horse.Style.NONE || slash < 0) return base;

        String markings = base.substring(0, slash + 1) + "horse_markings_" + assetWord(style.name());
        if (baby && atlas.has(markings + "_baby")) {
            markings = markings + "_baby";
        }

        return atlas.has(markings) ? atlas.layered(base, List.of(markings)) : base;
    }

    /**
     * The robe, trade and badge this villager wears, in the order vanilla draws them.
     *
     * <p>One difference from the client: vanilla hides the robe's hood under a profession hat by drawing the robe on
     * a second mesh with no hat cube, which needs the layers ordered rather than composited. Compositing lets the
     * hood show through wherever the hat texture is clear, so the two agree except on the two trades that wear a
     * headband rather than a hat.
     */
    private static String dressed(TextureAtlas atlas, String base, boolean baby, Villager.Type type, Villager.Profession profession, int level) {
        int slash = base.lastIndexOf('/');
        if (slash < 0 || type == null) return base;

        String directory = base.substring(0, slash + 1);
        List<String> layers = new ArrayList<>();

        // A young villager's robe comes out of a folder of its own and it wears no trade at all, which is vanilla's
        // rule rather than a shortcut here.
        layers.add(directory + (baby ? "baby/" : "type/") + type.getKey().value());

        String job = profession == null ? "none" : profession.getKey().value();
        if (!baby && !job.equals("none")) {
            layers.add(directory + "profession/" + job);
            // A nitwit has a trade texture and no level, which is the joke.
            if (!job.equals("nitwit")) {
                layers.add(directory + "profession_level/" + BADGES.get(Math.clamp(level, 1, BADGES.size()) - 1));
            }
        }

        layers.removeIf(layer -> !atlas.has(layer));
        return atlas.layered(base, layers);
    }

    /** One accessor, or null when this entity has no such method or it answers with nothing nameable. */
    private static Object called(Entity entity, String accessor) {
        try {
            return entity.getClass().getMethod(accessor).invoke(entity);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No such method on this type, which is the ordinary case and not worth a log line per entity.
            return null;
        }
    }

    /** What the assets would call this variant, whichever of the two shapes Bukkit hands it over as. */
    private static String word(Object variant) {
        if (variant instanceof Keyed keyed) return keyed.getKey().value();
        if (variant instanceof Enum<?> named) return named.name().toLowerCase(Locale.ROOT);

        return null;
    }

    /** {@code tropical_fish} to {@code TropicalFish}, which is how Bukkit spells an entity in a method name. */
    private static String camelCase(String type) {
        StringBuilder out = new StringBuilder(type.length());
        for (String word : type.split("_")) {
            if (!word.isEmpty()) {
                out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
            }
        }
        return out.toString();
    }

    /** The assets write these without the underscore: {@code DARK_BROWN} is {@code darkbrown}. */
    private static String assetWord(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }
}
