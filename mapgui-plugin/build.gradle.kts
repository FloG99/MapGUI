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
    compileOnly(libs.paper.api)
    // Never shipped: VideoLibraryLoader downloads these at runtime, and only when video.ffmpeg is on.
    compileOnly(libs.javacv)
    compileOnly(libs.ffmpeg)
    // Wall geometry is worth testing, and it speaks BlockFace. The API alone needs no server to load.
    testImplementation(libs.paper.api)
}

tasks {
    // Puts the sample video where the wall command looks for it, so /mapgui wall works right away.
    val installSampleVideos by registering(Copy::class) {
        from(rootProject.file("examples/media")) {
            include("*.gif")
        }
        into(layout.projectDirectory.dir("run/plugins/MapGUI/videos"))
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
        }
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        // Ship the examples into the test server so `runServer` is enough to try everything.
        pluginJars.from(
            project(":examples:gallery").tasks.named("jar").map { it.outputs.files },
            project(":examples:todo").tasks.named("jar").map { it.outputs.files },
            project(":examples:minimap").tasks.named("jar").map { it.outputs.files },
            project(":examples:camera").tasks.named("jar").map { it.outputs.files },
            project(":examples:claims").tasks.named("jar").map { it.outputs.files },
            project(":examples:walls").tasks.named("jar").map { it.outputs.files },
        )
        dependsOn(installSampleVideos)
    }
}
