plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":mapgui-api"))
    implementation(project(":mapgui-camera"))
    // runtimeOnly, and one line per supported version: the plugin finds its backend by name at startup, and
    // importing one would put a single version's server classes on the compile classpath for everything.
    runtimeOnly(project(":mapgui-nms-26_2"))
    runtimeOnly(project(":mapgui-nms-26_1"))
    compileOnly(libs.paper.api)
    // Never shipped: VideoLibraryLoader downloads these at runtime, and only when video.ffmpeg is on.
    compileOnly(libs.javacv)
    compileOnly(libs.ffmpeg)
    // Wall geometry is worth testing, and it speaks BlockFace. The API alone needs no server to load.
    testImplementation(libs.paper.api)
}

tasks {
    // The thin jar is nobody's: this module's classes without the api, the layout engine, the camera or a
    // version backend. It carries paper-plugin.yml though, so it looks like a plugin, loads, and then dies on
    // NoClassDefFoundError - which is a bad thing to leave sitting beside the real jar in build/libs.
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName = "MapGUI"
        archiveClassifier = ""
        // Only our own modules end up in here - there are no third-party runtime
        // dependencies to relocate. mapgui-layout is not listed: its classes are already inside the
        // mapgui-api jar, so including it as well would merge them twice.
        dependencies {
            include(project(":mapgui-api"))
            include(project(":mapgui-camera"))
            include(project(":mapgui-nms-26_2"))
            include(project(":mapgui-nms-26_1"))
        }
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        // The examples plugin, so `runServer` is enough to try everything - and the same one jar an admin
        // downloads, which is also what puts the sample video in place.
        pluginJars.from(project(":examples:bundle").tasks.named("shadowJar").map { it.outputs.files })
    }
}
