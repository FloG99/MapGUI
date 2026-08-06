plugins {
    alias(libs.plugins.paperweight.userdev)
}

// One module per Minecraft version, because each is compiled against that version's own server jar and
// nothing else can be. The rest of MapGUI never imports one: the plugin looks its backend up by name at
// startup, so several of these can sit in the same jar with only the right one ever loaded.
//
// To add a version: copy this directory, point the dev bundle below at the new Paper, add it to
// settings.gradle.kts, to the plugin's dependencies and shadowJar, and to the table in Backends.
dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())
    implementation(project(":mapgui-api"))
}
