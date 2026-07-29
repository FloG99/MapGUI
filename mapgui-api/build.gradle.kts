dependencies {
    // api(), so consumers only need to declare mapgui-api to get the DSL too.
    api(project(":mapgui-layout"))
    compileOnly(libs.paper.api)
    // Wall geometry is worth testing, and it speaks BlockFace. The API alone needs no server to load.
    testImplementation(libs.paper.api)
}
