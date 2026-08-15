package de.flog99.mapgui.plugin.video;

import java.util.Locale;

/**
 * Whether FFmpeg is on the classpath, and which build of it this machine needs.
 *
 * <p>MapGUI does not ship FFmpeg. It is around 80 MB of native code per platform, most servers never play a
 * video, and the licence on a full build is not one to hand out casually. So it is downloaded on demand
 * instead: turning on {@code video.ffmpeg} makes the plugin loader fetch exactly the two jars this operating
 * system and processor need, once, on the next start.
 *
 * <p>Shared with the loader, which runs before the plugin exists and so cannot ask it anything.
 */
public final class VideoNatives {

    /**
     * Kept in step with the versions in {@code gradle/libs.versions.toml}, which is what the plugin compiles
     * against. FFmpeg's version is the one that JavaCV release ships - the two move together and cannot be
     * mixed.
     */
    public static final String JAVACV_VERSION = "1.5.14";
    public static final String FFMPEG_VERSION = "8.1.2-1.5.14";

    private VideoNatives() {
    }

    /**
     * The classifier JavaCPP names this platform's natives with, such as {@code linux-x86_64}.
     *
     * <p>Only this one is downloaded. The convenience artifact that everybody reaches for first pulls every
     * platform there is, which is well over a gigabyte to run one video on one machine.
     */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String cpu = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> arch;
        };

        if (os.contains("win")) return "windows-" + cpu;
        if (os.contains("mac") || os.contains("darwin")) return "macosx-" + cpu;
        return "linux-" + cpu;
    }

    /**
     * Whether the classes actually arrived.
     *
     * <p>False when the admin has not turned it on, and also when they have but the download failed - the
     * server carries on either way, and a wall that wanted a video says so rather than the plugin refusing to
     * start.
     */
    public static boolean available() {
        try {
            Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
