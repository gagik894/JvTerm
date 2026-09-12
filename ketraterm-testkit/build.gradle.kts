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
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation(project(":ketraterm-core"))
    implementation(project(":ketraterm-host"))
    implementation(project(":ketraterm-parser"))
    implementation(project(":ketraterm-transport-api"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
}

val xtermOracleDirectory = rootProject.layout.projectDirectory.dir("tools/xterm-oracle")
val npmExecutable = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

fun Test.configureGeneratedDifferentialCampaign(defaultCases: Int) {
    systemProperty(
        "ketraterm.generatedDifferential.cases",
        providers.gradleProperty("xtermDifferentialCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.generatedDifferential.startIndex",
        providers.gradleProperty("xtermDifferentialStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.generatedDifferential.commitSha",
        providers
            .gradleProperty("xtermDifferentialCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.generatedDifferential.artifacts",
        providers
            .gradleProperty("xtermDifferentialArtifactDirectory")
            .getOrElse(
                layout.buildDirectory
                    .dir("reports/xterm-differential")
                    .get()
                    .asFile.absolutePath,
            ),
    )
}

val installXtermOracle =
    tasks.register<Exec>("installXtermOracle") {
        group = "verification"
        description = "Installs the version-pinned xterm.js differential oracle."
        workingDir(xtermOracleDirectory)
        commandLine(npmExecutable, "ci", "--ignore-scripts")
        inputs.files(
            xtermOracleDirectory.file("package.json"),
            xtermOracleDirectory.file("package-lock.json"),
        )
        outputs.dir(xtermOracleDirectory.dir("node_modules"))
    }

val testXtermOracle =
    tasks.register<Exec>("testXtermOracle") {
        group = "verification"
        description = "Runs the process-isolated xterm.js oracle unit tests."
        dependsOn(installXtermOracle)
        workingDir(xtermOracleDirectory)
        commandLine(npmExecutable, "test")
    }

tasks.register<Test>("xtermDifferentialTest") {
    group = "verification"
    description = "Runs KetraTerm against the pinned xterm.js differential oracle."
    dependsOn(installXtermOracle, testXtermOracle, tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*Xterm*OracleTest")
    systemProperty("ketraterm.xtermOracle.required", "true")
    systemProperty("ketraterm.xtermOracle.node", "node")
    systemProperty(
        "ketraterm.xtermOracle.script",
        xtermOracleDirectory.file("oracle.mjs").asFile.absolutePath,
    )
    systemProperty("ketraterm.xtermOracle.workingDirectory", xtermOracleDirectory.asFile.absolutePath)
    configureGeneratedDifferentialCampaign(defaultCases = 2000)
}

fun registerGeneratedDifferentialProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(installXtermOracle, testXtermOracle, tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*XtermGeneratedDifferentialOracleTest")
    systemProperty("ketraterm.xtermOracle.required", "true")
    systemProperty("ketraterm.xtermOracle.node", "node")
    systemProperty("ketraterm.xtermOracle.script", xtermOracleDirectory.file("oracle.mjs").asFile.absolutePath)
    systemProperty("ketraterm.xtermOracle.workingDirectory", xtermOracleDirectory.asFile.absolutePath)
    configureGeneratedDifferentialCampaign(defaultCases)
}

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialSmokeTest",
    descriptionText = "Runs 100 deterministic generated xterm.js differential cases.",
    defaultCases = 100,
)

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialNightlyTest",
    descriptionText = "Runs 100,000 deterministic generated xterm.js differential cases.",
    defaultCases = 100_000,
)

registerGeneratedDifferentialProfile(
    taskName = "xtermDifferentialReleaseAudit",
    descriptionText = "Runs 500,000 deterministic generated xterm.js differential cases.",
    defaultCases = 500_000,
)

fun registerResizeReflowInvariantProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*TerminalResizeReflowInvariantCampaignTest")
    systemProperty("ketraterm.resizeReflow.required", "true")
    systemProperty(
        "ketraterm.resizeReflow.cases",
        providers.gradleProperty("resizeReflowCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.resizeReflow.startIndex",
        providers.gradleProperty("resizeReflowStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.resizeReflow.commitSha",
        providers
            .gradleProperty("resizeReflowCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.resizeReflow.artifacts",
        layout.buildDirectory
            .dir("reports/resize-reflow-invariant")
            .get()
            .asFile.absolutePath,
    )
}

registerResizeReflowInvariantProfile(
    taskName = "resizeReflowInvariantSmokeTest",
    descriptionText = "Runs 100 deterministic state-aware resize/reflow invariant cases.",
    defaultCases = 100,
)

registerResizeReflowInvariantProfile(
    taskName = "resizeReflowInvariantNightlyTest",
    descriptionText = "Runs 10,000 deterministic state-aware resize/reflow invariant cases.",
    defaultCases = 10_000,
)

fun registerCursorWrapModelProfile(
    taskName: String,
    descriptionText: String,
    defaultCases: Int,
) = tasks.register<Test>(taskName) {
    group = "verification"
    description = descriptionText
    dependsOn(tasks.testClasses)
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter.includeTestsMatching("*TerminalCursorWrapModelCampaignTest")
    systemProperty("ketraterm.cursorWrap.required", "true")
    systemProperty(
        "ketraterm.cursorWrap.cases",
        providers.gradleProperty("cursorWrapCases").getOrElse(defaultCases.toString()),
    )
    systemProperty(
        "ketraterm.cursorWrap.startIndex",
        providers.gradleProperty("cursorWrapStartIndex").getOrElse("0"),
    )
    systemProperty(
        "ketraterm.cursorWrap.commitSha",
        providers
            .gradleProperty("cursorWrapCommitSha")
            .orElse(providers.environmentVariable("GITHUB_SHA"))
            .getOrElse("unknown"),
    )
    systemProperty(
        "ketraterm.cursorWrap.artifacts",
        layout.buildDirectory
            .dir("reports/cursor-wrap-model")
            .get()
            .asFile.absolutePath,
    )
}

registerCursorWrapModelProfile(
    taskName = "cursorWrapModelSmokeTest",
    descriptionText = "Runs 100 deterministic model-based cursor and deferred-wrap cases.",
    defaultCases = 100,
)

registerCursorWrapModelProfile(
    taskName = "cursorWrapModelNightlyTest",
    descriptionText = "Runs 25,000 deterministic model-based terminal grid-physics cases.",
    defaultCases = 25_000,
)

tasks.test {
    useJUnitPlatform()
}
