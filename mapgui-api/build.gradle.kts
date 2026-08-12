// The layout engine ships INSIDE this artifact rather than beside it on Maven Central. It stays its own
// module, which is what keeps Bukkit out of it, but nothing consumes it on its own - so publishing it
// separately only ever meant two coordinates and two jars to find for one dependency.
val layoutSources = rootProject.file("mapgui-layout/src/main/java")
val layoutClasses = project(":mapgui-layout").layout.buildDirectory.dir("classes/java/main")

dependencies {
    // api(), so everything in this build that uses the DSL still compiles against it directly.
    api(project(":mapgui-layout"))
    compileOnly(libs.paper.api)
    // Wall geometry is worth testing, and it speaks BlockFace. The API alone needs no server to load.
    testImplementation(libs.paper.api)
}

// The compiled classes rather than the jar unpacked: zipTree belongs to the script object, which a task
// cannot carry across the configuration cache. The module has no resources to bring along.
tasks.jar {
    dependsOn(":mapgui-layout:classes")
    from(layoutClasses)
}

tasks.named<Jar>("sourcesJar") {
    from(layoutSources)
}

tasks.javadoc {
    source(layoutSources)
}

// Gradle module metadata is generated from the real dependency graph and so names mapgui-layout whatever the
// POM says - and a Gradle consumer prefers it over the POM. There is no supported way to edit it, so it is not
// published. Nothing here needs variant-aware resolution: it is one jar for one platform.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}

publishing.publications.withType<MavenPublication>().configureEach {
    // api(project) would otherwise put a dependency on a coordinate that is no longer published, and a POM
    // naming an artifact Central does not have breaks every consumer that resolves it.
    pom.withXml {
        val dependencies = asNode().children().filterIsInstance<groovy.util.Node>()
                .firstOrNull { (it.name() as? groovy.namespace.QName)?.localPart == "dependencies" } ?: return@withXml

        dependencies.children().filterIsInstance<groovy.util.Node>()
                .filter { entry -> entry.get("artifactId").let { it is groovy.util.NodeList && it.text() == "mapgui-layout" } }
                .forEach { dependencies.remove(it) }
    }
}
