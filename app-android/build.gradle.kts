// app-android — Lumen2D Studio: project manager + full 2D editor + game runtime.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.lumen2d.studio"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.lumen2d.studio"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release APKs are signed with the debug keystore unless a real one is supplied through CI
    // secrets, so every CI build is installable on a device. When no keystore exists at all we
    // fall back to AGP's own `debug` signing config, which keeps a clean checkout buildable.
    val keystorePath = providers.environmentVariable("LUMEN_KEYSTORE").orNull
        ?: "${System.getProperty("user.home")}/.android/debug.keystore"
    val keystoreExists = file(keystorePath).isFile

    signingConfigs {
        if (keystoreExists) {
            create("ciRelease") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("LUMEN_KEYSTORE_PASSWORD").orNull ?: "android"
                keyAlias = providers.environmentVariable("LUMEN_KEY_ALIAS").orNull ?: "androiddebugkey"
                keyPassword = providers.environmentVariable("LUMEN_KEY_PASSWORD").orNull ?: "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (keystoreExists) signingConfigs.getByName("ciRelease") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        // The editor UI is hand-drawn from framework views (see StudioTheme.kt): no Compose, no
        // Material, nothing that can drift from the engine's own rendering.
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // Engine content ships uncompressed so the runtime can mmap/decode assets quickly.
        noCompress += listOf("wav", "png", "json", "lumen", "tmx", "tsx", "fnt")
    }
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-android"))

    // FileProvider (sharing exported .lumenzip archives) is the only androidx API used.
    implementation(libs.androidx.core.ktx)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// ---------------------------------------------------------------- engine content
//
// The APK ships the engine's own generated content:
//   assets/packs/**        the asset library, mounted as `lib://packs` (base + imported packs)
//   assets/sources/**      the per-file provenance index the editor shows next to an asset
//   assets/samples/**      the sample games, seeded into app storage on first launch
//
// All of it is regenerated from the engine itself (`--export-assets` / `--export-samples`, or
// `gradle :engine-desktop:exportContent`), so the APK can never ship content that the current
// engine would not produce.
val stageEngineContent by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages assets-library/ and sample-games/ into the APK's assets folder."
    dependsOn(":engine-desktop:exportContent")
    from(rootProject.file("assets-library/packs")) { into("packs") }
    from(rootProject.file("assets-library/sources")) { into("sources") }
    from(rootProject.file("sample-games")) { into("samples") }
    into(layout.buildDirectory.dir("engine-content"))
}

android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("engine-content"))

tasks.named("preBuild") { dependsOn(stageEngineContent) }
