// One plugin holding every demo, so trying MapGUI is two jars in plugins/ and nothing to unpack. A module each
// keeps "copy this package into your own plugin" the unit of reuse.
plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":examples:gallery"))
    implementation(project(":examples:todo"))
    implementation(project(":examples:minimap"))
    implementation(project(":examples:camera"))
    implementation(project(":examples:claims"))
    implementation(project(":examples:walls"))
}

tasks {
    // The video an admin places with /mapgui wall place, carried in the jar - see SampleVideo.
    processResources {
        from(rootProject.file("examples/media")) {
            include("polish-cow-transparent.gif")
        }
    }

    shadowJar {
        archiveBaseName = "MapGUI-examples"
        archiveClassifier = ""
        // Nothing third-party: the demos take mapgui-api and paper-api compileOnly.
    }

    assemble {
        dependsOn(shadowJar)
    }
}
