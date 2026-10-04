// Lumen2D — root project build script.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // No Compose plugin: the editor is built from framework views on purpose (see app-android).
}

tasks.register("engineInfo") {
    group = "help"
    description = "Prints the Lumen2D engine version and module map."
    doLast {
        println("Lumen2D engine v${libs.versions.lumen2d.get()} — modules: " +
            project(":engine-core").path + ", " + project(":engine-desktop").path + ", " +
            project(":engine-android").path + ", " + project(":app-android").path)
    }
}
