// One plugin holding every demo, so trying MapGUI is two jars in plugins/ and nothing to unpack. The demos stay
// a module each: that boundary is what keeps "copy this package into your own plugin" the unit of reuse, and it
// stops one demo quietly leaning on another.
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
    // The video an admin places with /mapgui wall place. Carried in the jar rather than downloaded beside it -
    // see SampleVideo, which writes it out where that command looks.
    processResources {
        from(rootProject.file("examples/media")) {
            include("polish-cow-transparent.gif")
        }
    }

    shadowJar {
        archiveBaseName = "MapGUI-examples"
        archiveClassifier = ""
        // Nothing third-party lands in here: the demos take mapgui-api and paper-api compileOnly, so the only
        // classes shadow finds are the demos themselves.
    }

    assemble {
        dependsOn(shadowJar)
    }
}
