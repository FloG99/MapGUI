plugins {
    // Lets Gradle fetch a JDK 25 toolchain itself, so a fresh clone builds without one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "MapGUI"

include(
    "mapgui-layout",
    "mapgui-api",
    "mapgui-nms",
    "mapgui-plugin",
    "mapgui-preview",
    "examples:gallery",
    "examples:todo",
    "examples:minimap",
    "examples:claims",
    "examples:walls",
)
