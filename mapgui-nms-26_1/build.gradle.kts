plugins {
    alias(libs.plugins.paperweight.userdev)
}

// The 26.1 backend. See mapgui-nms-26_2/build.gradle.kts for why there is one module per Minecraft version
// and what adding another one takes - this module is a copy of it against 26.1's own dev bundle.
dependencies {
    paperweight.paperDevBundle(libs.versions.paper261.get())
    implementation(project(":mapgui-api"))
}
