package de.flog99.mapgui.plugin.video;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;

/**
 * Puts FFmpeg on the classpath before the plugin loads, if the admin asked for it.
 *
 * <p>This is the same bargain the camera makes with textures, and for the same reasons: the thing is large,
 * most servers do not want it, and downloading it silently on everyone's behalf would be rude. So it is off
 * until {@code video.ffmpeg} is turned on, and then it is fetched once and cached by the server like any
 * other plugin library.
 *
 * <p>Read straight off disk rather than through the plugin's config, because a loader runs before there is a
 * plugin to ask. A missing or unreadable file reads as off, which is the safe way round.
 *
 * <p>Only this platform's natives are listed. The artifact everyone reaches for first bundles every operating
 * system and architecture, which is over a gigabyte to play one video on one machine.
 */
public final class VideoLibraryLoader implements PluginLoader {

    /**
     * Everything, so nothing comes along for the ride.
     *
     * <p>JavaCV's own dependencies are every device and vision library it can drive - OpenCV, OpenBLAS,
     * Tesseract, three depth cameras and a FireWire binding. Asked for transitively that is 119 MB of jars to
     * decode an mp4, so each artifact below is named and nothing else is taken.
     */
    private static final Exclusion EVERYTHING_ELSE = new Exclusion("*", "*", "*", "*");

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpath) {
        if (!enabled(classpath.getContext().getDataDirectory().toFile())) return;

        String platform = VideoNatives.platform();
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        // Paper's mirror rather than Maven Central, which asks not to be used as a CDN and whose terms this
        // would otherwise be breaking on behalf of every server running MapGUI.
        resolver.addRepository(new RemoteRepository.Builder("central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        resolver.addDependency(dependency("org.bytedeco:javacv:" + VideoNatives.JAVACV_VERSION));
        resolver.addDependency(dependency("org.bytedeco:javacpp:" + VideoNatives.JAVACV_VERSION));
        resolver.addDependency(dependency("org.bytedeco:javacpp:jar:" + platform + ":" + VideoNatives.JAVACV_VERSION));
        resolver.addDependency(dependency("org.bytedeco:ffmpeg:" + VideoNatives.FFMPEG_VERSION));
        resolver.addDependency(dependency("org.bytedeco:ffmpeg:jar:" + platform + ":" + VideoNatives.FFMPEG_VERSION));

        classpath.addLibrary(resolver);
    }

    private static Dependency dependency(String coordinates) {
        return new Dependency(new DefaultArtifact(coordinates), null, false, List.of(EVERYTHING_ELSE));
    }

    private static boolean enabled(File dataDirectory) {
        File config = new File(dataDirectory, "config.yml");
        if (!config.isFile()) return false;

        return YamlConfiguration.loadConfiguration(config).getBoolean("video.ffmpeg", false);
    }
}
