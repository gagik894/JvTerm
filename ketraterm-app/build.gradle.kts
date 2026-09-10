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
    application
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String

    implementation("com.formdev:flatlaf:3.7.1")
    implementation("com.formdev:flatlaf-extras:3.7.1")
    implementation(project(":ketraterm-completion"))
    implementation(project(":ketraterm-completion-host"))
    implementation(project(":ketraterm-completion-persistence"))
    implementation(project(":ketraterm-ui-swing"))
    implementation(project(":ketraterm-ui-swing-host"))
    implementation(project(":ketraterm-workspace"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.ketraterm.app.KetraTermAppKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val appVersion = project.version.toString()
    inputs.property("version", appVersion)
    filesMatching("**/version.properties") {
        expand(mapOf("version" to appVersion))
    }
}

val printNativeVersion =
    tasks.register<JavaExec>("printNativeVersion") {
        dependsOn(tasks.named("compileKotlin"))
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.github.ketraterm.app.deployment.VersionNormalizationKt")
        args(project.version.toString())
    }

val writeNativeVersion =
    tasks.register<JavaExec>("writeNativeVersion") {
        dependsOn(tasks.named("compileKotlin"))
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("io.github.ketraterm.app.deployment.VersionNormalizationKt")

        val outputFile = layout.buildDirectory.file("native-version.txt")
        outputs.file(outputFile)

        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    project.version.toString(),
                    outputFile.get().asFile.absolutePath,
                )
            },
        )
    }

val prepareJpackageInput =
    tasks.register<Sync>("prepareJpackageInput") {
        dependsOn(tasks.named("jar"))
        into(layout.buildDirectory.dir("jpackage/input"))

        from(tasks.named("jar")) {
            rename { "ketraterm-app.jar" }
        }

        from(
            configurations.runtimeClasspath
                .get()
                .incoming.files,
        )

        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
