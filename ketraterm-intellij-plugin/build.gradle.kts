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

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import java.util.zip.ZipFile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
    id("com.diffplug.spotless") version "8.8.0"
}

val repositoryVersionProvider =
    providers.fileContents(layout.projectDirectory.file("../VERSION")).asText.map { it.trim() }
val isReleaseBuild =
    providers.environmentVariable("RELEASE").map { it.equals("true", ignoreCase = true) }.orElse(false)
val pluginVersionProvider =
    repositoryVersionProvider.zip(isReleaseBuild) { repositoryVersion, isRelease ->
        if (isRelease) repositoryVersion else "$repositoryVersion-SNAPSHOT"
    }
val intellijIdeaVersion = providers.gradleProperty("intellijIdeaVersion")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")
val pluginPublishChannel = providers.gradleProperty("pluginPublishChannel")
val pluginJavaVersion = 25
val pluginDescription =
    """
    <p>KetraTerm is a fast, modern terminal for IntelliJ Platform IDEs, built for shells, command-line tools, and rich TUI applications that developers keep open all day.</p>
    <p>It provides project-aware terminal tabs, quick shell-profile launch actions, IDE color-scheme integration, configurable typography and cursor behavior, scrollback, paste handling, visual bell support, and guarded clipboard/title controls.</p>
    <p>KetraTerm focuses on contemporary terminal workflows: responsive rendering, Unicode-aware text, modern color and notification sequences, and security-conscious handling of terminal-initiated IDE actions.</p>
    """.trimIndent()
val pluginChangeNotes =
    providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText.map { changelog ->
        changelog
            .lineSequence()
            .dropWhile { line -> !line.startsWith("## ") }
            .drop(1)
            .takeWhile { line -> !line.startsWith("## ") }
            .joinToString("\n")
            .trim()
            .ifBlank { "See CHANGELOG.md for release notes." }
    }

version = pluginVersionProvider.get()

private val ketratermVersion = version.toString()

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("io.github.ketraterm:ketraterm-completion:$ketratermVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.github.ketraterm:ketraterm-completion-host:$ketratermVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.github.ketraterm:ketraterm-completion-persistence:$ketratermVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.github.ketraterm:ketraterm-ui-swing-host:$ketratermVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.github.ketraterm:ketraterm-ui-swing:$ketratermVersion") {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    implementation("io.github.ketraterm:ketraterm-workspace:$ketratermVersion") {
        exclude(group = "org.jetbrains.pty4j", module = "pty4j")
        exclude(group = "net.java.dev.jna", module = "jna")
        exclude(group = "net.java.dev.jna", module = "jna-platform")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(intellijIdeaVersion)
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.gradle")
        // Since 2026.2, the Git and Gradle bundled plugins expose these APIs
        // from separately resolved platform modules rather than the plugin JARs.
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.log")
        bundledModule("intellij.platform.vcs.log.graph")
        bundledModule("intellij.gradle")
        bundledModule("intellij.gradle.settings")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

// IntelliJ loads its bundled Kotlin runtime parent-first. Shipping another
// stdlib in the plugin cannot override that runtime and can hide incompatible
// API usage during packaging, so keep every transitive stdlib variant out.
listOf("runtimeClasspath", "testRuntimeClasspath").forEach { configurationName ->
    configurations.named(configurationName) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}

intellijPlatform {
    pluginConfiguration {
        id.set("io.github.ketraterm.terminal")
        name.set("KetraTerm")
        version.set(project.version.toString())
        description.set(pluginDescription)
        changeNotes.set(pluginChangeNotes)

        ideaVersion {
            sinceBuild.set(pluginSinceBuild)
        }

        vendor {
            name.set("KetraTerm")
            url.set("https://github.com/ketraterm/KetraTerm")
        }
    }

    publishing {
        channels.set(listOf(pluginPublishChannel.get()))
    }
}

kotlin {
    jvmToolchain(pluginJavaVersion)
    compilerOptions {
        allWarningsAsErrors.set(true)
        // Avoid compatibility bridges for inherited IntelliJ Kotlin interface defaults.
        // The plugin has no public Kotlin interface ABI to preserve.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.3.1")
        licenseHeaderFile(file("../gradle/license-header.txt"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1")
    }
}

val pluginDistributionArchives =
    layout.buildDirectory.dir("distributions").map { directory ->
        directory.asFileTree.matching { include("*.zip") }
    }
val verifyNoBundledKotlinStdlib =
    tasks.register("verifyNoBundledKotlinStdlib") {
        group = "verification"
        description = "Verifies that the plugin archive relies on IntelliJ's bundled Kotlin runtime."
        dependsOn(tasks.named("buildPlugin"))
        inputs.files(pluginDistributionArchives)

        doLast {
            val archives = inputs.files.files
            check(archives.isNotEmpty()) { "No IntelliJ plugin distribution was produced" }

            val bundledStdlibEntries =
                archives.flatMap { archive ->
                    ZipFile(archive).use { pluginArchive ->
                        pluginArchive
                            .entries()
                            .asSequence()
                            .map { entry -> entry.name }
                            .filter { entryName ->
                                entryName.contains("/lib/kotlin-stdlib") && entryName.endsWith(".jar")
                            }.map { entryName -> "${archive.name}: $entryName" }
                            .toList()
                    }
                }
            check(bundledStdlibEntries.isEmpty()) {
                "IntelliJ plugins must not bundle Kotlin stdlib: ${bundledStdlibEntries.joinToString()}"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyNoBundledKotlinStdlib)
}
