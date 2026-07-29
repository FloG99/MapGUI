dependencies {
    implementation(project(":mapgui-api"))
    // On the runtime classpath rather than compileOnly: the real map font and palette are plain
    // data tables, so they work in a bare JVM with no server running.
    implementation(libs.paper.api)
}

// The screen being previewed is deliberately NOT a dependency. It is loaded from its compiled
// output directory by a child classloader so a rebuild is picked up without a restart, and
// parent-first delegation would defeat that if it were also on this module's classpath.
val defaultScreen = "de.flog99.mapgui.examples.gallery.GalleryScreen"
val defaultModule = "examples/gallery"

fun screenName() = providers.gradleProperty("screen").getOrElse(defaultScreen)

fun classesDir(): String {
    val module = providers.gradleProperty("module").getOrElse(defaultModule)
    return rootProject.layout.projectDirectory.dir("$module/build/classes/java/main").asFile.path
}

fun backdrop() = providers.gradleProperty("backdrop").getOrElse("")

// ./gradlew preview -Pscreen=com.example.MyScreen -Pmodule=examples/todo
tasks.register<JavaExec>("preview") {
    group = "mapgui"
    description = "Renders a screen to a PNG without starting a server"
    dependsOn(":$defaultModule:classes".replace('/', ':'))
    mainClass = "de.flog99.mapgui.preview.PreviewRender"
    classpath = sourceSets["main"].runtimeClasspath
    args(
        screenName(),
        classesDir(),
        providers.gradleProperty("out")
            .getOrElse(layout.buildDirectory.file("preview.png").get().asFile.path),
        providers.gradleProperty("scale").getOrElse("4"),
        backdrop(),
    )
}

// ./gradlew previewServe   (alongside `./gradlew -t classes` in another terminal)
tasks.register<JavaExec>("previewServe") {
    group = "mapgui"
    description = "Serves a live preview at http://127.0.0.1:7654 and re-renders on rebuild"
    dependsOn(":$defaultModule:classes".replace('/', ':'))
    mainClass = "de.flog99.mapgui.preview.PreviewServer"
    classpath = sourceSets["main"].runtimeClasspath
    args(
        screenName(),
        classesDir(),
        providers.gradleProperty("port").getOrElse("7654"),
        backdrop(),
    )
}
