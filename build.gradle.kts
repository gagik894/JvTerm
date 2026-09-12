/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.8.0"
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

extra["kotlinxCoroutinesVersion"] = "1.10.2"

repositories {
    mavenCentral()
}

dependencies {
    dokka(project(":ketraterm-protocol"))
    dokka(project(":ketraterm-parser"))
    dokka(project(":ketraterm-core"))
    dokka(project(":ketraterm-host"))
    dokka(project(":ketraterm-input"))
    dokka(project(":ketraterm-completion"))
    dokka(project(":ketraterm-completion-host"))
    dokka(project(":ketraterm-completion-persistence"))
    dokka(project(":ketraterm-render-api"))
    dokka(project(":ketraterm-render-cache"))
    dokka(project(":ketraterm-transport-api"))
    dokka(project(":ketraterm-session"))
    dokka(project(":ketraterm-ui-swing"))
    dokka(project(":ketraterm-ui-swing-host"))
    dokka(project(":ketraterm-testkit"))
    dokka(project(":ketraterm-pty"))
    dokka(project(":ketraterm-workspace"))
}

val versionFile = rootProject.file("VERSION")
val baseVersion = if (versionFile.exists()) {
    versionFile.readText().trim()
} else {
    "0.1.0"
}
val isRelease = System.getenv("RELEASE") == "true"
val projectVersion = if (isRelease) baseVersion else "$baseVersion-SNAPSHOT"

subprojects {
    group = "io.github.ketraterm"
    version = projectVersion

    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(25)
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        if (name != "ketraterm-benchmarks" && name != "ketraterm-app" && name != "ketraterm-testkit") {
            plugins.apply("com.vanniktech.maven.publish")

            extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = true)
                signAllPublications()

                pom {
                    name.set(project.name)
                    description.set("ketraterm terminal emulator library - subproject ${project.name}")
                    url.set("https://github.com/ketraterm/ketraterm")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("gsargsyan")
                            name.set("Gagik Sargsyan")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/ketraterm/ketraterm.git")
                        developerConnection.set("scm:git:ssh://github.com/ketraterm/ketraterm.git")
                        url.set("https://github.com/ketraterm/ketraterm")
                    }
                }
            }
        }
    }

    plugins.apply("com.diffplug.spotless")
    plugins.apply("org.jetbrains.dokka")

    plugins.withId("org.jetbrains.dokka") {
        if (file("Module.md").exists()) {
            extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
                dokkaPublications.configureEach {
                    includes.from("Module.md")
                }
            }
        }
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.3.1")
            licenseHeaderFile(rootProject.file("gradle/license-header.txt"))
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint("1.3.1")
        }
    }
}



