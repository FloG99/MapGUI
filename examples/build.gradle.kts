// The examples are one plugin, examples:bundle, depending on MapGUI exactly like a third party would -
// compileOnly, with the implementation supplied by the MapGUI plugin at runtime. Everything else here is a demo
// each, with no descriptor of its own: the bundle carries the only one, the way your own plugin would.
subprojects {
    dependencies {
        "compileOnly"(project(":mapgui-api"))
        "compileOnly"(rootProject.libs.paper.api)
        // Testing your own model without a server is part of the point, so the same two on the test path.
        "testImplementation"(project(":mapgui-api"))
        "testImplementation"(rootProject.libs.paper.api)
    }
}
