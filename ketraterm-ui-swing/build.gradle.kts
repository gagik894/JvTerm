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

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java-library")
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Keep benchmarks of internal helpers in the same Kotlin target for Gradle and IDE visibility.
val jmh = sourceSets.create("jmh")
kotlin.target.compilations.named(jmh.name) {
    associateWith(kotlin.target.compilations.getByName("main"))
}

dependencies {
    val kotlinxCoroutinesVersion = rootProject.extra["kotlinxCoroutinesVersion"] as String

    api(project(":ketraterm-session"))
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    implementation(project(":ketraterm-input"))
    implementation(project(":ketraterm-render-api"))
    implementation(project(":ketraterm-render-cache"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinxCoroutinesVersion")

    add(jmh.implementationConfigurationName, "org.openjdk.jmh:jmh-core:1.37")
}

// The central JMH runner generates the harness and packages these Kotlin benchmark classes.
configurations.consumable("benchmarkElements") {
    extendsFrom(configurations[jmh.runtimeClasspathConfigurationName])
    outgoing.artifact(tasks.named<KotlinCompile>("compileJmhKotlin").flatMap { it.destinationDirectory })
}

tasks.test {
    useJUnitPlatform()
}
