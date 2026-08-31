plugins {
    // For `clean` and a build directory at the root, which the Central bundle is assembled in.
    base
    alias(libs.plugins.run.paper) apply false
}

// Version is overridable, so tagging a release is `-Pversion=1.0.0` rather than a commit that edits this
// file. The group is a Maven Central namespace verified through the GitHub account, which is why it does not
// match the de.flog99 package names - nothing requires the two to agree.
allprojects {
    group = "io.github.flog99"
    version = rootProject.providers.gradleProperty("version").getOrElse("1.0.0-SNAPSHOT")
}

/**
 * What a plugin compiles against, and so the only thing worth putting in a repository. One coordinate, with
 * the layout engine bundled into it - see mapgui-api/build.gradle.kts.
 */
val published = setOf("mapgui-api")

// Everything for Central lands in one directory across both modules, so it can be zipped into a single
// bundle - the only shape the Central Portal accepts.
val stagingDir = layout.buildDirectory.dir("staging-deploy")

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    dependencies {
        "compileOnly"(rootProject.libs.annotations)
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(rootProject.libs.versions.java.get())
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = rootProject.libs.versions.java.get().toInt()
        options.compilerArgs.add("-Xlint:all,-serial,-processing")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = "UTF-8"
        val props = mapOf(
            "version" to project.version,
            // The lowest version MapGUI supports, not the one it is built against: api-version is the floor
            // Paper refuses to load below, so naming the build version would lock out every older server the
            // backend modules do cover.
            "apiVersion" to rootProject.libs.versions.minecraftMin.get(),
        )
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
            expand(props)
        }
    }

    if (name !in published) return@subprojects

    apply(plugin = "maven-publish")

    // Only the module anyone compiles against carries sources and javadoc jars. Central requires both, and
    // building them for the plugin and the examples would be output nobody reads.
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:all,-missing", "-quiet")
            links("https://jd.papermc.io/paper/${rootProject.libs.versions.minecraft.get()}/")
        }
    }

    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = "Interactive GUIs drawn onto Minecraft maps, with an auto-layout engine"
                url = "https://github.com/FloG99/MapGUI"
                licenses {
                    license {
                        name = "LGPL-3.0-or-later"
                        url = "https://www.gnu.org/licenses/lgpl-3.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "FloG99"
                        name = "FloG99"
                        url = "https://github.com/FloG99"
                    }
                }
                scm {
                    url = "https://github.com/FloG99/MapGUI"
                    connection = "scm:git:https://github.com/FloG99/MapGUI.git"
                    developerConnection = "scm:git:ssh://git@github.com/FloG99/MapGUI.git"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/FloG99/MapGUI/issues"
                }
            }
        }

        // A directory rather than a server: the Portal takes one signed bundle rather than a deploy per
        // artifact, so the upload happens once from the root task below.
        repositories.maven {
            name = "staging"
            url = stagingDir.get().asFile.toURI()
        }
    }

    // Only when a key is present, so an ordinary build and publishToMavenLocal need no GPG at all.
    val signingKey = providers.environmentVariable("MAVEN_GPG_PRIVATE_KEY")
    if (signingKey.isPresent) {
        apply(plugin = "signing")
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("MAVEN_GPG_PASSPHRASE").getOrElse(""))
            sign(extensions.getByType<PublishingExtension>().publications)
        }
    }
}

// The bundle to hand to the Central Portal, which the release workflow uploads. Central rejects a snapshot,
// so this is only meaningful with -Pversion= set to a real version.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Zips the signed artifacts of the published modules for the Maven Central Portal"

    // Read now rather than in the task, since reaching for the project at execution time is exactly what
    // the configuration cache forbids.
    val releasing = version.toString()

    dependsOn(published.map { ":$it:publishAllPublicationsToStagingRepository" })
    from(stagingDir) {
        // The Portal derives versions from the tree itself and rejects a bundle carrying these.
        exclude("**/maven-metadata.xml*")
    }
    archiveFileName = "central-bundle.zip"
    destinationDirectory = layout.buildDirectory

    doFirst {
        require(!releasing.endsWith("SNAPSHOT")) {
            "Maven Central will not take $releasing - build with -Pversion=1.0.0"
        }
    }
}
