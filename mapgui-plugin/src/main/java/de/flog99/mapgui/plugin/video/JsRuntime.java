package de.flog99.mapgui.plugin.video;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The arguments that make yt-dlp run inside this plugin's folder rather than on this machine: its JavaScript
 * runtime, its cache, and its configuration.
 *
 * <p><b>YouTube's media urls are gated behind a challenge script that has to be run.</b> Without a JavaScript
 * runtime yt-dlp earns 403s and throttled connections on any video whose signature needs solving, which for a
 * wall means a stream that never starts. The runtime is a quickjs-ng binary of MapGUI's own, pinned by hash in
 * {@code media-tools.properties} and test-executed by {@link Toolchain#jsRuntime} before it is offered, because
 * a binary that exists is not the same as one antivirus will let run.
 *
 * <p><b>A deno, node or bun installed on the machine is deliberately not used</b>, for the reasons in
 * {@link Toolchain}: which of four programs solved the challenge would otherwise be a property of the host
 * rather than of this plugin, and two servers seeing different 403 behaviour would have one more axis to differ
 * on before anything could be reproduced.
 *
 * <p>A runtime alone is not enough: yt-dlp also needs the <b>EJS solver scripts</b>, which a pip install ships
 * without. Missing, signature solving fails and YouTube answers 403 on every video - with the runtime line
 * reading perfectly healthy, which is what makes it so hard to find. {@code --remote-components ejs:npm} lets
 * yt-dlp fetch them; an official build makes it a no-op rather than a conflict.
 */
final class JsRuntime {

    /**
     * The arguments yt-dlp needs, before the url.
     *
     * <p>Never empty, and never refuses: a missing runtime has already been reported, loudly, by
     * {@link Toolchain#jsRuntime}, and then the attempt is made anyway. That is deliberate - plenty of videos
     * resolve without solving anything, and refusing them all to be consistent about a runtime would take away
     * playback that works.
     */
    static List<String> ytdlpArgs(Toolchain tools) {
        List<String> args = new ArrayList<>(List.of(
                // Not the machine's yt-dlp configuration. A config file in the server user's home directory
                // could set a format, a proxy or an output template underneath us, which is the same class of
                // surprise as using the machine's yt-dlp: what this plugin does should be what it was asked to.
                "--ignore-config",
                // Nor the machine's cache. yt-dlp's default is ${XDG_CACHE_HOME}/yt-dlp - the server user's home
                // - and it is written to on every resolve: signature functions, and the EJS solver below.
                // Pointed here, everything this plugin causes to exist lives in one deletable folder.
                "--cache-dir", tools.cacheDir().toAbsolutePath().toString(),
                "--remote-components", "ejs:npm"));

        Path runtime = tools.jsRuntime();
        if (runtime != null) {
            // By path rather than by name, so yt-dlp runs this exact binary and not a quickjs that happens to be
            // on PATH. Only quickjs is named at all: naming deno or node as well would let an installed one win,
            // which is the behaviour this file exists to have stopped.
            args.addAll(List.of("--js-runtimes", "quickjs:" + runtime));
        }
        return List.copyOf(args);
    }

    private JsRuntime() {
    }
}
