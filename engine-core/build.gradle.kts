// engine-core — pure Kotlin (no Android APIs), so it compiles and unit-tests on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The test suite is a self-contained runner (dev.lumen2d.core.test.TestMain) with zero
// external test dependencies, so the exact same suite runs under Gradle, under the
// offline tools/build-local.sh script, and on CI.
tasks.register<JavaExec>("engineTests") {
    group = "verification"
    description = "Runs the Lumen2D engine-core test suite."
    dependsOn("testClasses")
    mainClass.set("dev.lumen2d.core.test.TestMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    args = (project.findProperty("filter")?.toString()?.let { listOf(it) } ?: emptyList())
}
