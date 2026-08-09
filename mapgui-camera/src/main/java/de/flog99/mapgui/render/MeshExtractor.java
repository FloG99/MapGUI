package de.flog99.mapgui.render;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real vanilla entity geometry, by running the client's own mesh builders.
 *
 * <p>Minecraft ships entity shapes only as compiled code, but the classes that build them are pure data:
 * {@code CubeListBuilder} and {@code PartDefinition} touch no OpenGL and no world, and
 * {@code LayerDefinition.bakeRoot()} hands back a tree of cubes with the texture coordinates already worked out. So
 * the geometry is <i>executed</i> out of the jar rather than copied out of it, and comes back exact by construction.
 *
 * <p>The factory name per model comes from {@link EntityMeshes} rather than being searched for: reflection hands back
 * declared methods in no particular order, so "the first one that returns a layer" picks a different model between
 * runs - the eyes layer of a warden one time and its body the next.
 *
 * <p>{@code LayerDefinitions.createRoots()} would be vanilla's own table of all 358 layers in one call, and is not
 * used: it needs the registries bootstrapped, and {@code Bootstrap.bootStrap()} replaces {@code System.out} and
 * {@code System.err} with log4j wrappers for the whole JVM.
 */
final class MeshExtractor {

    /** Present in a client jar and in nothing else, and the class whose fields this reads. */
    static final String MODEL_CLASS = "net/minecraft/client/model/geom/ModelPart.class";

    /**
     * How far up vanilla lifts a model before drawing it, in entity pixels.
     *
     * <p>{@code LivingEntityRenderer} turns the model upside down and then translates it 1.501 blocks, which is
     * what puts a model built downward from the neck onto the ground. The half-thousandth is vanilla's own and is
     * there to keep coincident surfaces from fighting.
     */
    static final float GROUND = 1.501f * 16;

    /** How near two coordinates have to be to count as the same plane, in entity pixels. */
    private static final float EPSILON = 1e-4f;

    private final ClassLoader loader;
    private final Class<?> layerDefinition;
    private final Class<?> meshDefinition;
    private final Class<?> modelPart;
    private final Class<?> cubeDeformation;
    private final Field cubesField;
    private final Field childrenField;
    private final Field poseField;

    private MeshExtractor(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        this.layerDefinition = load("net.minecraft.client.model.geom.builders.LayerDefinition");
        this.meshDefinition = load("net.minecraft.client.model.geom.builders.MeshDefinition");
        this.modelPart = load("net.minecraft.client.model.geom.ModelPart");
        this.cubeDeformation = load("net.minecraft.client.model.geom.builders.CubeDeformation");

        this.cubesField = modelPart.getDeclaredField("cubes");
        this.childrenField = modelPart.getDeclaredField("children");
        this.poseField = modelPart.getDeclaredField("initialPose");
        cubesField.setAccessible(true);
        childrenField.setAccessible(true);
        poseField.setAccessible(true);
    }

    /**
     * Bakes every mesh {@link EntityMeshes} asks for out of a client jar.
     *
     * <p>Twice over when the first pass leaves something out: the handful of meshes whose class reaches the game's
     * registries need those filled first, which is expensive and so is only paid for once something has asked.
     *
     * @param parent the plugin's own loader, which supplies the shared libraries - see {@link #bake}
     * @return mesh name to its root parts, in entity space, missing any entry that would not bake
     */
    static Map<String, List<MeshPart>> extract(Path jar, ClassLoader parent, List<EntityMeshes.Spec> specs) throws IOException, ReflectiveOperationException {
        Map<String, List<MeshPart>> meshes = new LinkedHashMap<>();
        List<EntityMeshes.Spec> missing = bake(jar, parent, specs, false, meshes);
        if (!missing.isEmpty()) {
            bake(jar, parent, missing, true, meshes);
        }
        return meshes;
    }

    /**
     * One pass over a list of specs, into {@code meshes}.
     *
     * <p>The loader is child first for {@code net.minecraft} and parent first for everything else. That is not a
     * detail: a Paper server already has {@code net.minecraft} classes of its own, and letting half of a model's
     * dependencies come from the server and half from the jar is how you get a mesh builder holding a codec that
     * disagrees with it. The libraries below - guava, fastutil, netty, gson and the rest - do come from the
     * parent, because those the server has at the matching version and the jar does not contain them at all.
     *
     * <p>The loader is also why a failed mesh gets a second pass rather than a retry: a class whose initializer threw
     * is marked erroneous for the life of the loader it was loaded by, so the only way back to it is a new one.
     *
     * @param registries whether to bootstrap the game before baking - see {@link #bootstrap}
     * @return the specs this pass did not produce
     */
    private static List<EntityMeshes.Spec> bake(Path jar, ClassLoader parent, List<EntityMeshes.Spec> specs,
                                                boolean registries, Map<String, List<MeshPart>> meshes) throws IOException, ReflectiveOperationException {
        URL[] classpath = {jar.toUri().toURL()};

        // The loader outside the extractor rather than owned by it, because a jar that is not a client jar fails
        // in the constructor - and a loader that leaked there would hold the file open, which on Windows means
        // nothing can delete it afterwards.
        try (URLClassLoader loader = new ClientLoader("mapgui-client-mesh", classpath, parent)) {
            if (registries) {
                bootstrap(loader);
            }

            MeshExtractor extractor = new MeshExtractor(loader);
            List<EntityMeshes.Spec> missing = new ArrayList<>();
            for (EntityMeshes.Spec spec : specs) {
                List<MeshPart> parts = extractor.bake(spec);
                if (parts.isEmpty()) {
                    missing.add(spec);
                } else {
                    meshes.put(spec.mesh(), parts);
                }
            }
            return missing;
        }
    }

    /**
     * The registries a mesh's own class reads, as the registry field and the class that fills it.
     *
     * <p>A decorated pot is the only one in 26.2. Its geometry is pure data like everything else here, but it is built
     * by {@code DecoratedPotRenderer}, whose static fields map every sherd item to a sprite - so touching the class at
     * all runs {@code DECORATED_POT_PATTERN.getOrThrow}, and an empty registry refuses to answer. The mesh is not what
     * needs a registry; the class it happens to live on is.
     */
    private static final Map<String, String> REGISTRIES = Map.of(
            "DECORATED_POT_PATTERN", "net.minecraft.world.level.block.entity.DecoratedPotPatterns");

    /**
     * Opens the game's registries inside the jar's own loader and fills the few {@link #REGISTRIES} names, for the
     * meshes that cannot be reached without them.
     *
     * <p>Not {@code Bootstrap.bootStrap()}, which is the obvious answer and the wrong one. That builds every block,
     * item and entity type in the game: five seconds and a hundred megabytes per call, it replaces {@code System.out}
     * and {@code System.err} for the whole JVM on its way out, and it leaves log4j holding loggers that pin the
     * throwaway loader in memory. Running eleven extractions in one JVM was enough to exhaust a test worker's heap.
     * What is wanted here is two dozen pot patterns.
     *
     * <p>So the flag that guards the registries is set directly and only the wanted ones are filled. The flag is
     * vanilla's own order rather than a trick played on it: {@code bootStrap()} sets it before it fills anything,
     * because filling a registry is what checks it.
     *
     * <p>Second pass rather than always, and best effort throughout: a jar that will not give this up loses the
     * meshes that needed it and keeps every other one, which is the same bargain the rest of this class makes.
     */
    private static void bootstrap(ClassLoader loader) {
        try {
            Field bootstrapped = Class.forName("net.minecraft.server.Bootstrap", true, loader)
                    .getDeclaredField("isBootstrapped");
            bootstrapped.setAccessible(true);
            bootstrapped.setBoolean(null, true);

            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader);
            Class<?> registry = Class.forName("net.minecraft.core.Registry", true, loader);

            for (Map.Entry<String, String> wanted : REGISTRIES.entrySet()) {
                Object filled = registries.getField(wanted.getKey()).get(null);
                Class.forName(wanted.getValue(), true, loader).getMethod("bootstrap", registry).invoke(null, filled);

                // Frozen as well as filled, which is not tidiness: registering an entry leaves the holder that names
                // it unbound, and freezing is what binds them. Read back before that, every one of them throws.
                filled.getClass().getMethod("freeze").invoke(filled);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // The meshes that wanted this stay missing, and their block draws from its json instead.
        }
    }

    /**
     * One mesh, which is usually one layer and occasionally two - a slime is a cube inside a bigger holed cube,
     * and both come off the same texture, so they are one mesh rather than two models to order against each other.
     *
     * @return empty if any of its layers would not bake, since half a mob is worse than the bounding box
     */
    private List<MeshPart> bake(EntityMeshes.Spec spec) {
        List<MeshPart> parts = new ArrayList<>();
        for (EntityMeshes.Layer layer : spec.layers()) {
            try {
                parts.add(root(layer));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // LinkageError as well as the rest, and it is not hypothetical: a class whose static fields reach the
                // block registry cannot initialize outside a running game, and thrown out of here that one mesh took
                // every other mesh in the jar down with it.
                return List.of();
            }
        }
        return MeshPart.withHeads(parts);
    }

    private MeshPart root(EntityMeshes.Layer layer) throws ReflectiveOperationException {
        Class<?> type = load(qualified(layer.type()));
        Method factory = factory(type, layer.factory());
        factory.setAccessible(true);

        Object made = factory.invoke(null, arguments(factory, layer.numbers()));
        // A factory that hands back a set of four rather than one mesh: armor is built head, chest, legs and feet
        // together, because the slots share a body and differ only in which parts of it they inflate.
        if (layer.component() != null) {
            made = made.getClass().getMethod(layer.component()).invoke(made);
        }

        Object built = scaled(made, layer.scale());
        Object root = layer.textureWidth() > 0
                ? bakeMesh(built, layer.textureWidth(), layer.textureHeight())
                : bakeLayer(built);

        // A second bake to pose, so a pose that fails halfway leaves the plain one untouched. Baking is cheap and
        // happens once per version; a mesh half-animated by an exception would be a mob standing wrong forever.
        Object posed = layer.textureWidth() > 0
                ? bakeMesh(built, layer.textureWidth(), layer.textureHeight())
                : bakeLayer(built);

        // Which tree to read, and which of a part's two poses to read it from: {@code initialPose} is where the
        // geometry was authored, and the live fields are where the animation left it.
        boolean animated = stood(posed, layer);
        MeshPart part = part("root", animated ? posed : root, animated, layer.space());

        // Anything not drawn by LivingEntityRenderer is already standing where it belongs: nothing to turn over and
        // nothing to lift. A block entity moves by half a block on top of that, since its model is measured from the
        // block's corner and everything downstream is measured about the middle of what it is drawing.
        float middle = layer.space() == EntityMeshes.Space.BLOCK ? HALF_BLOCK : 0;
        float lift = layer.space() == EntityMeshes.Space.MOB ? GROUND : 0;

        return new MeshPart(part.name(), part.head(), part.x() - middle, part.y() + lift, part.z() - middle,
                part.xRot(), part.yRot(), part.zRot(), part.xScale(), part.yScale(), part.zScale(),
                part.cubes(), part.children());
    }

    /** Where the middle of a block sits in the pixels its model is measured in. */
    static final float HALF_BLOCK = 8;

    /**
     * The class a mesh factory lives on, which is under the model package for all but a few.
     *
     * <p>Vanilla keeps a handful of block entity meshes on the renderer that draws them instead - a conduit's shell
     * and a decorated pot's sides are built by {@code ConduitRenderer} and {@code DecoratedPotRenderer}, and nowhere
     * else. Those are named here with the {@code renderer.} they really live under; everything else keeps the short
     * name it has always had.
     */
    private static String qualified(String type) {
        return type.startsWith(RENDERERS) ? "net.minecraft.client." + type : "net.minecraft.client.model." + type;
    }

    private static final String RENDERERS = "renderer.";

    /**
     * The named factory, searched up the hierarchy because the shared bases are where several of them live.
     *
     * <p>Chosen by parameter count and then by parameter type when a class overloads the name, rather than by
     * whatever {@code getDeclaredMethods} happens to return first: that order is unspecified, and a model picked
     * arbitrarily is a mesh that changes between runs of the same build.
     */
    private static Method factory(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            Method chosen = null;
            for (Method candidate : at.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && (chosen == null || earlier(candidate, chosen))) {
                    chosen = candidate;
                }
            }
            if (chosen != null) return chosen;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static boolean earlier(Method candidate, Method than) {
        if (candidate.getParameterCount() != than.getParameterCount()) {
            return candidate.getParameterCount() < than.getParameterCount();
        }
        return Arrays.toString(candidate.getParameterTypes()).compareTo(Arrays.toString(than.getParameterTypes())) < 0;
    }

    /**
     * A rest-pose call: no deformation, not a baby, and whatever numbers the table stated.
     *
     * <p>Every mesh factory that takes arguments at all takes some subset of these, and the defaults are what the
     * layer that entity actually renders with passes - a helmet layer is the one that wants an inflated cube, and a
     * helmet is not something a camera draws.
     */
    private Object[] arguments(Method factory, float[] numbers) throws ReflectiveOperationException {
        Class<?>[] types = factory.getParameterTypes();
        Object[] out = new Object[types.length];
        int number = 0;

        for (int i = 0; i < types.length; i++) {
            if (types[i] == cubeDeformation) {
                // Inflated by the stated amount when one is given. Armor is the same humanoid mesh grown a little,
                // so the inflation is the entire difference between a body and the plate over it.
                out[i] = number < numbers.length
                        ? cubeDeformation.getConstructor(float.class).newInstance(numbers[number++])
                        : cubeDeformation.getField("NONE").get(null);
            } else if (types[i] == int.class) {
                out[i] = 0;
            } else if (types[i] == float.class) {
                out[i] = number < numbers.length ? numbers[number++] : 0f;
            } else if (types[i] == boolean.class) {
                // Off unless the table says otherwise, which is what the flag means everywhere it appears: a banner
                // asks whether it is standing rather than hanging on a wall, and a wall banner has no pole.
                out[i] = number < numbers.length && numbers[number++] != 0;
            } else {
                throw new NoSuchMethodException(factory + " wants a " + types[i] + ", which this cannot supply");
            }
        }
        return out;
    }

    /**
     * The client's own {@code MeshTransformer.scaling}, rather than a scale of our own applied afterwards.
     *
     * <p>Not the same thing: scaling a mesh multiplies every part's pose <i>and</i> sets its scale factors, so a
     * cube ends up somewhere no single outer multiplication puts it. Running vanilla's transformer is the whole
     * point - a cave spider comes out exactly the size the client draws one, with no arithmetic here to be wrong.
     */
    private Object scaled(Object built, float scale) throws ReflectiveOperationException {
        if (scale == 0) return built;

        Class<?> meshTransformer = load("net.minecraft.client.model.geom.builders.MeshTransformer");
        Object transformer = meshTransformer.getMethod("scaling", float.class).invoke(null, scale);
        return built.getClass().getMethod("apply", meshTransformer).invoke(built, transformer);
    }

    private Object bakeLayer(Object built) throws ReflectiveOperationException {
        Method bake = layerDefinition.getDeclaredMethod("bakeRoot");
        bake.setAccessible(true);
        return bake.invoke(built);
    }

    /** For the factories that hand back a bare mesh, which carries no texture size and so has to be told one. */
    private Object bakeMesh(Object built, int width, int height) throws ReflectiveOperationException {
        Object root = meshDefinition.getDeclaredMethod("getRoot").invoke(built);
        Method bake = root.getClass().getDeclaredMethod("bake", int.class, int.class);
        bake.setAccessible(true);
        return bake.invoke(root, width, height);
    }

    /**
     * One {@code ModelPart} as our own, in the space vanilla actually draws a model in.
     *
     * <p>That space is reached by {@code PoseStack.scale(-1, -1, 1)}, which {@code LivingEntityRenderer} applies to
     * every living entity. Both signs matter: {@code diag(-1, -1, 1)} is a half turn about Z and keeps handedness,
     * while flipping Y alone is a <b>reflection</b> that stands the model up and leaves it mirrored - invisible on a
     * symmetric texture, and a pig's tail curling the wrong way on anything else.
     *
     * <p>The rotations follow by conjugation: a half turn about Z reverses X and Y, so a rest rotation about X or Y
     * negates and one about Z is left alone.
     */
    @SuppressWarnings("unchecked")
    private MeshPart part(String name, Object source, boolean animated, EntityMeshes.Space space) throws ReflectiveOperationException {
        List<MeshCube> cubes = new ArrayList<>();
        for (Object cube : (List<Object>) cubesField.get(source)) {
            cubes.add(cube(cube, space));
        }

        List<MeshPart> children = new ArrayList<>();
        for (Map.Entry<String, Object> child : ((Map<String, Object>) childrenField.get(source)).entrySet()) {
            children.add(part(child.getKey(), child.getValue(), animated, space));
        }

        float[] pose = animated ? standing(source) : authored(source);

        // Turned over for a mob and left alone for a block entity, for the reason on Layer#block. Both end up in
        // the same space - what a mob's renderer reaches by flipping, a block entity's model is authored in.
        float flip = space.flipped() ? -1 : 1;

        // Which part turns with the head is decided by {@link MeshPart#withHeads} once the tree is whole.
        return new MeshPart(
                name, false,
                flip * pose[0], flip * pose[1], pose[2],
                flip * pose[3], flip * pose[4], pose[5],
                pose[6], pose[7], pose[8],
                List.copyOf(cubes), List.copyOf(children)
        );
    }

    /** Where the geometry was authored, which is what a mesh nobody could pose has to fall back to. */
    private float[] authored(Object source) throws ReflectiveOperationException {
        Object pose = poseField.get(source);
        Class<?> type = pose.getClass();

        return new float[]{
                component(type, pose, "x"), component(type, pose, "y"), component(type, pose, "z"),
                component(type, pose, "xRot"), component(type, pose, "yRot"), component(type, pose, "zRot"),
                component(type, pose, "xScale"), component(type, pose, "yScale"), component(type, pose, "zScale")
        };
    }

    /**
     * Where the client's own animation left the part, which is the pose the mob is really standing in.
     *
     * <p>A {@code ModelPart} carries both: {@code initialPose} is where the geometry was written and these nine fields
     * are what the renderer moves every frame. Reading the first is how a zombie came out with its arms at its sides -
     * that is where they are authored, and {@code setupAnim} is what lifts them.
     */
    private float[] standing(Object source) throws ReflectiveOperationException {
        float[] pose = new float[9];
        String[] names = {"x", "y", "z", "xRot", "yRot", "zRot", "xScale", "yScale", "zScale"};
        for (int i = 0; i < names.length; i++) {
            pose[i] = (float) modelPart.getField(names[i]).get(source);
        }
        return pose;
    }

    /**
     * Runs the client's own {@code setupAnim} over a freshly baked tree, so the mesh carries the pose that mob stands
     * in rather than the rest pose its geometry was authored in.
     *
     * <p>A mesh is only where the boxes are; for a good many mobs part of the client's animation is not motion at all
     * but how the thing stands - the undead hold their arms out, a spider's legs splay. Transcribing those angles
     * here was wrong twice over: wrong per mob, since each model class holds its own, and wrong in principle, since
     * the client already knows.
     *
     * <p>Nothing that moves is asked for. The render state is left at its defaults - no walk, no swing, no age, head
     * straight - so what comes out is the mob standing still, which is the one pose a photograph can claim.
     *
     * <p>Best effort, like the extraction around it: an abstract model class cannot be built, a render state may want
     * an object this cannot conjure, and a version may rename any of it. In each case the mesh keeps its rest pose.
     *
     * @return whether the tree really was posed
     */
    private boolean stood(Object root, EntityMeshes.Layer layer) {
        try {
            Class<?> model = load("net.minecraft.client.model." + (layer.pose() != null ? layer.pose() : layer.type()));
            if (Modifier.isAbstract(model.getModifiers())) return false;

            Object instance = model.getConstructor(load("net.minecraft.client.model.geom.ModelPart")).newInstance(root);
            Method setup = setupAnim(model);
            if (setup == null) return false;

            setup.setAccessible(true);
            setup.invoke(instance, standingStill(setup.getParameterTypes()[0]));
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * The model's own {@code setupAnim}, which is the most specific of the several it inherits.
     *
     * <p>Every model has one per level of its hierarchy plus a bridge taking {@code Object}, and calling the wrong one
     * skips the pose that makes the mob that mob - a zombie's arms are in {@code ZombieModel}'s and nowhere else.
     */
    private static Method setupAnim(Class<?> model) {
        Method best = null;
        for (Method method : model.getMethods()) {
            if (!method.getName().equals("setupAnim") || method.getParameterCount() != 1) continue;

            Class<?> state = method.getParameterTypes()[0];
            if (state == Object.class) continue;
            if (best == null || best.getParameterTypes()[0].isAssignableFrom(state)) {
                best = method;
            }
        }
        return best;
    }

    /**
     * A render state describing a mob standing still and looking straight ahead.
     *
     * <p>Zero is the answer for nearly every field, which is the point: no walk, no swing, no partial tick. The two
     * kinds that need help are enums, which start null and would be switched on, and item stacks, which a model asks
     * about to decide how a hand is held.
     */
    private Object standingStill(Class<?> state) throws ReflectiveOperationException {
        // A few models are posed by a number rather than by a render state - an end crystal by its age, and at rest
        // that is zero. Without this they come back unposed, and an unposed end crystal is three glass shells built
        // in the same 8x8x8 box: the pose is the whole of what tells them apart.
        if (state == Float.class || state == float.class) return 0f;
        if (state == Double.class || state == double.class) return 0d;

        // Or by something with no state at all, which is what a copper golem statue's Unit is saying: there is one
        // of these and it is the one.
        if (state.isEnum()) return resting(state);

        // Or by a record of angles, which a book's openness and two page flips are. All zero is the shut book, and a
        // record has no empty constructor to fill in afterwards - it is built whole or not at all.
        if (state.isRecord()) return blank(state);

        Object made = state.getConstructor().newInstance();

        for (Class<?> level = state; level != null && level != Object.class; level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;

                field.setAccessible(true);
                if (field.getType().isEnum() && field.get(made) == null) {
                    field.set(made, resting(field.getType()));
                } else if (field.getType().getName().endsWith("item.ItemStack") && field.get(made) == null) {
                    field.set(made, empty(field.getType()));
                }
            }
        }
        return made;
    }

    /** A record with every component at rest, through its canonical constructor since that is the only way in. */
    private static Object blank(Class<?> record) throws ReflectiveOperationException {
        RecordComponent[] components = record.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];

        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = still(types[i]);
        }
        return record.getDeclaredConstructor(types).newInstance(values);
    }

    /** What one component of such a record is when nothing is happening, which for every angle in one is zero. */
    private static Object still(Class<?> type) {
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        return null;
    }

    /** The constant that means "nothing in particular", which is what a mob doing nothing in particular is in. */
    private static Object resting(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        for (String name : List.of("DEFAULT", "EMPTY", "NONE", "STANDING", "IDLE")) {
            for (Object constant : constants) {
                if (((Enum<?>) constant).name().equals(name)) return constant;
            }
        }
        return constants.length == 0 ? null : constants[0];
    }

    private static Object empty(Class<?> itemStack) {
        try {
            return itemStack.getField("EMPTY").get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Touching ItemStack can pull registries in behind it. A null stack is what the field already held.
            return null;
        }
    }

    private static float component(Class<?> poseType, Object pose, String name) throws ReflectiveOperationException {
        return (float) poseType.getMethod(name).invoke(pose);
    }

    /**
     * One cube as its bounds and the texture coordinates of each corner it draws.
     *
     * <p>The bounds come from the vertices and not from the cube's own {@code minX..maxZ} fields, which are
     * deliberate: those are the box as it was asked for, before any {@code CubeDeformation} grew or shrank it,
     * while the vertices are what the client actually draws. An inflated overlay - a sheep's fleece, a charged
     * creeper's aura, armor - differs between the two by exactly the inflation.
     *
     * <p>Which face a quad is comes from its geometry rather than its normal, because a mirrored cube has its
     * winding reversed and its normals with it, and geometry cannot lie about which plane it lies in.
     */
    private MeshCube cube(Object source, EntityMeshes.Space space) throws ReflectiveOperationException {
        Object[] polygons = (Object[]) source.getClass().getField("polygons").get(source);

        float[][] positions = new float[polygons.length][];
        float[][] coordinates = new float[polygons.length][];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        float[][] normals = new float[polygons.length][];

        for (int i = 0; i < polygons.length; i++) {
            Object[] vertices = (Object[]) polygons[i].getClass().getMethod("vertices").invoke(polygons[i]);
            positions[i] = new float[vertices.length * 3];
            coordinates[i] = new float[vertices.length * 2];
            normals[i] = normal(polygons[i], space);

            for (int v = 0; v < vertices.length; v++) {
                Class<?> vertexType = vertices[v].getClass();
                float flip = space.flipped() ? -1 : 1;
                float x = flip * (float) vertexType.getMethod("x").invoke(vertices[v]);
                float y = flip * (float) vertexType.getMethod("y").invoke(vertices[v]);
                float z = (float) vertexType.getMethod("z").invoke(vertices[v]);

                positions[i][v * 3] = x;
                positions[i][v * 3 + 1] = y;
                positions[i][v * 3 + 2] = z;
                coordinates[i][v * 2] = (float) vertexType.getMethod("u").invoke(vertices[v]);
                coordinates[i][v * 2 + 1] = (float) vertexType.getMethod("v").invoke(vertices[v]);

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
        }

        MeshCube cube = new MeshCube(minX, minY, minZ, maxX, maxY, maxZ, new float[6][]);
        for (int i = 0; i < polygons.length; i++) {
            assign(cube, positions[i], coordinates[i], normals[i]);
        }
        return cube;
    }

    /** The outward normal turned the same way as the positions, or it would name the opposite side of the cube. */
    private static float[] normal(Object polygon, EntityMeshes.Space space) throws ReflectiveOperationException {
        Object vector = polygon.getClass().getMethod("normal").invoke(polygon);
        // Turned with the vertices or not turned with them, but never one without the other: the normal is what
        // says which side of the cube a quad is, so a normal flipped against its own positions puts the lid
        // texture underneath the chest.
        float flip = space.flipped() ? -1 : 1;
        return new float[]{
                flip * (float) vector.getClass().getMethod("x").invoke(vector),
                flip * (float) vector.getClass().getMethod("y").invoke(vector),
                (float) vector.getClass().getMethod("z").invoke(vector)
        };
    }

    /** Files one quad's corner UVs under the side of the cube it lies on. */
    private static void assign(MeshCube cube, float[] positions, float[] coordinates, float[] normal) {
        int count = positions.length / 3;
        int axis = axisOf(positions, count, normal);
        float[] lows = {cube.minX(), cube.minY(), cube.minZ()};
        float[] highs = {cube.maxX(), cube.maxY(), cube.maxZ()};
        float at = positions[axis];

        // Which of the two sides, from where the quad sits - except on an axis the cube has no thickness on, where
        // both sides are the same plane and only the normal can tell them apart.
        boolean low = highs[axis] - lows[axis] < EPSILON
                ? normal[axis] < 0
                : Math.abs(at - lows[axis]) <= Math.abs(at - highs[axis]);

        Direction face = switch (axis) {
            case 0 -> low ? Direction.WEST : Direction.EAST;
            case 1 -> low ? Direction.DOWN : Direction.UP;
            default -> low ? Direction.NORTH : Direction.SOUTH;
        };
        if (cube.faces()[face.ordinal()] != null) {
            return;
        }

        float[] corners = new float[8];
        boolean[] filled = new boolean[4];
        for (int v = 0; v < count; v++) {
            float x = positions[v * 3];
            float y = positions[v * 3 + 1];
            float z = positions[v * 3 + 2];
            int slot = MeshCube.corner(cube.across(face, x, y, z) > 0.5, cube.down(face, x, y, z) > 0.5);
            corners[slot * 2] = coordinates[v * 2];
            corners[slot * 2 + 1] = coordinates[v * 2 + 1];
            filled[slot] = true;
        }

        // A side with no area - the edge of a fin, the flank of a cube an armor layer inflated out of something
        // flat - has two of its corners in the same place, so only two slots get written. Copying across the axis
        // that collapsed leaves a rectangle of no width rather than two corners reading texel zero. Only the slots
        // vanilla actually wrote are copied from, or the second gap would be filled from the first one filled.
        boolean[] written = filled.clone();
        for (int slot = 0; slot < 4; slot++) {
            for (int flip = 1; !filled[slot] && flip < 4; flip++) {
                int from = slot ^ flip;
                if (written[from]) {
                    corners[slot * 2] = corners[from * 2];
                    corners[slot * 2 + 1] = corners[from * 2 + 1];
                    filled[slot] = true;
                }
            }
        }
        cube.faces()[face.ordinal()] = corners;
    }

    /**
     * The axis a quad's plane is normal to.
     *
     * <p>Geometry first, since it cannot lie: a quad whose four vertices agree on one coordinate lies in that
     * plane, whatever a normal that a mirrored cube reversed says. It is only a paper-thin cube - a fin, a wing,
     * a cape - that leaves two axes to choose from, and there the normal is the tie-break.
     */
    private static int axisOf(float[] positions, int count, float[] normal) {
        int chosen = -1;
        float best = -1;

        for (int axis = 0; axis < 3; axis++) {
            float low = Float.MAX_VALUE;
            float high = -Float.MAX_VALUE;
            for (int v = 0; v < count; v++) {
                low = Math.min(low, positions[v * 3 + axis]);
                high = Math.max(high, positions[v * 3 + axis]);
            }
            if (high - low > EPSILON) {
                continue;
            }
            if (Math.abs(normal[axis]) > best) {
                best = Math.abs(normal[axis]);
                chosen = axis;
            }
        }
        return chosen < 0 ? 0 : chosen;
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

}
