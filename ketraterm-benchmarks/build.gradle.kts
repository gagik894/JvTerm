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

import me.champeau.jmh.JmhBytecodeGeneratorTask

plugins {
    kotlin("jvm")
    id("me.champeau.jmh") version "0.7.3"
}

repositories {
    mavenCentral()
}

val swingBenchmarkClasses =
    configurations.create("swingBenchmarkClasses") {
        isCanBeConsumed = false
        isTransitive = false
    }
configurations.named("jmhImplementation") {
    extendsFrom(swingBenchmarkClasses)
}

dependencies {
    val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String

    implementation(project(":ketraterm-completion"))
    implementation(project(":ketraterm-completion-host"))
    implementation(project(":ketraterm-core"))
    implementation(project(":ketraterm-host"))
    implementation(project(":ketraterm-parser"))
    implementation(project(":ketraterm-render-api"))
    implementation(project(":ketraterm-render-cache"))
    implementation(project(":ketraterm-protocol"))
    implementation(project(":ketraterm-session"))
    implementation(project(":ketraterm-input"))
    implementation(project(":ketraterm-ui-swing"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    swingBenchmarkClasses(project(path = ":ketraterm-ui-swing", configuration = "benchmarkElements"))
}

// Generate one harness and benchmark list for both modules in the existing runnable JAR.
tasks.named<JmhBytecodeGeneratorTask>("jmhRunBytecodeGenerator") {
    classesDirsToProcess.from(swingBenchmarkClasses)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    benchmarkMode.set(listOf("thrpt"))
    timeUnit.set("ms")
    profilers.set(listOf("gc"))
}
