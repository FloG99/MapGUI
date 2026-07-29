plugins {
    alias(libs.plugins.paperweight.userdev)
}

// The only module that touches net.minecraft.*, so it's also the only one that needs
// the dev bundle download. Everything else builds against plain paper-api.
dependencies {
    paperweight.paperDevBundle(libs.versions.paper.get())
    implementation(project(":mapgui-api"))
}
