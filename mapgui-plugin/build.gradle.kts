plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":mapgui-api"))
    implementation(project(":mapgui-nms"))
    compileOnly(libs.paper.api)
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
        // dependencies to relocate.
        dependencies {
            include(project(":mapgui-layout"))
            include(project(":mapgui-api"))
            include(project(":mapgui-nms"))
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
            project(":examples:claims").tasks.named("jar").map { it.outputs.files },
            project(":examples:walls").tasks.named("jar").map { it.outputs.files },
        )
        dependsOn(installSampleVideos)
    }
}
