plugins {
    // Lets Gradle fetch a JDK 25 toolchain itself, so a fresh clone builds without one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MapGUI"

include(
    "mapgui-layout",
    "mapgui-api",
    "mapgui-camera",
    // One per Minecraft version. Adding a version adds a line here; see mapgui-nms-26_2/build.gradle.kts.
    "mapgui-nms-26_2",
    "mapgui-plugin",
    "mapgui-preview",
    // One plugin for all the demos, then a module each for the demos themselves.
    "examples:bundle",
    "examples:gallery",
    "examples:todo",
    "examples:minimap",
    "examples:camera",
    "examples:claims",
    "examples:walls",
    "examples:sketch",
)
