// Examples are standalone plugins that depend on MapGUI exactly like a third party
// would - compileOnly, with the implementation supplied by the MapGUI plugin at runtime.
subprojects {
    dependencies {
        "compileOnly"(project(":mapgui-api"))
        "compileOnly"(rootProject.libs.paper.api)
        // Testing your own model without a server is part of the point, so the same two on the test path.
        "testImplementation"(project(":mapgui-api"))
        "testImplementation"(rootProject.libs.paper.api)
    }

    tasks.named<Jar>("jar") {
        // Named for where it ends up: "todo.jar" in a plugins folder says nothing about what put it there.
        archiveBaseName = "MapGUI-example-${project.name}"
    }
}

// One release asset, so an admin can try the demos without building the repository. Deleting a jar is the
// only off switch they need, which is why none of this is a config option.
tasks.register<Zip>("examplesZip") {
    group = "distribution"
    description = "Zips the example plugins for a release asset"

    archiveBaseName = "MapGUI-examples"
    archiveVersion = version.toString()
    destinationDirectory = layout.buildDirectory

    from(subprojects.map { it.tasks.named<Jar>("jar") })
    // Something for `/mapgui wall place` to find. The large sample is left out because the gallery jar already
    // carries it as a resource, and a second copy would be most of the download.
    from(rootProject.file("examples/media")) {
        include("*.gif")
        exclude("bunny_sample_squared.gif")
        into("MapGUI/videos")
    }
}
