// The camera renderer, and like mapgui-layout it deliberately has no Bukkit dependency: ray math, voxel
// traversal and asset reading are all testable in a bare JVM, and geometry is exactly the kind of thing that
// needs testing - an axis the wrong way round renders a world mirrored, which is not visible in the code.
//
// Not published. It reaches a plugin through the small surface in mapgui-api, the same arrangement as
// mapgui-nms, and is shaded into MapGUI.jar.
dependencies {
    // For blockstate and model json. Every other module gets gson transitively from paper-api; this one has
    // no paper-api, so it declares it - compileOnly, because the server supplies it at runtime.
    compileOnly(libs.gson)
    testImplementation(libs.gson)
}
