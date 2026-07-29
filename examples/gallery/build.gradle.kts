// The sample video lives in examples/media so it is in the repository once, rather than copied into
// this module's resources as well. It is packaged from there.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("examples/media")) {
        include("*.gif")
    }
}
