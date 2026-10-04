// engine-desktop — JVM/AWT backends for the engine: windowed player, headless renderer,
// screenshot & preview-video tools, and the CLI used by CI to smoke-test sample games.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":engine-core"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("dev.lumen2d.desktop.LumenDesktopKt")
}

tasks.named<JavaExec>("run") {
    // Headless-safe defaults for CI containers.
    systemProperty("java.awt.headless", "true")
}

// ------------------------------------------------------------------ engine content
//
// The asset library and the sample games are *generated* by the engine, not hand-maintained: a
// breaking change to a node or script API makes these tasks (and the `--check` smoke tests) fail.
// The Android app stages their output into the APK assets.

tasks.register<JavaExec>("exportAssets") {
    group = "build"
    description = "Regenerates assets-library/packs with the current engine."
    dependsOn("classes")
    mainClass.set("dev.lumen2d.desktop.LumenDesktopKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--export-assets", rootProject.file("assets-library/packs").absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("exportSamples") {
    group = "build"
    description = "Regenerates sample-games/ with the current engine."
    dependsOn("classes")
    mainClass.set("dev.lumen2d.desktop.LumenDesktopKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("--export-samples", rootProject.file("sample-games").absolutePath)
    systemProperty("java.awt.headless", "true")
}

tasks.register("exportContent") {
    group = "build"
    description = "Regenerates the asset library and the sample games, then smoke-tests them."
    dependsOn("exportAssets", "exportSamples")
}

// One headless check per sample game, over the level scene rather than the menu: the menu has no
// physics bodies, so checking it would pass while the platformer level was broken.
val sampleGames = rootProject.file("sample-games")
    .listFiles()
    ?.filter { it.isDirectory && it.resolve("project.lumen").isFile }
    ?.sortedBy { it.name }
    ?: emptyList()

val sampleCheckTasks = sampleGames.map { game ->
    tasks.register<JavaExec>("check${game.name.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) }}") {
        group = "verification"
        description = "Headless smoke test of the ${game.name} sample game."
        dependsOn("classes")
        mainClass.set("dev.lumen2d.desktop.LumenDesktopKt")
        classpath = sourceSets["main"].runtimeClasspath
        systemProperty("java.awt.headless", "true")
        val scene = listOf("level_1", "space", "level", "menu")
            .map { game.resolve("scenes/$it.scene.json") }
            .firstOrNull { it.isFile }
        args = buildList {
            add("--check"); add(game.absolutePath)
            if (scene != null) { add("--scene"); add("scenes/${scene.name}") }
        }
    }
}

tasks.register("checkSamples") {
    group = "verification"
    description = "Runs every sample game headlessly (scripts, assets, physics, renderer)."
    dependsOn(sampleCheckTasks)
}
