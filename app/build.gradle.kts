/*
 * Podroid — Rootless Podman for Android
 *
 * A headless AArch64 QEMU micro-VM running Alpine Linux with Podman,
 * accessed via built-in serial terminal.
 */
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android)
}

val podroidQemuVersion = providers.gradleProperty("podroidQemuVersion").get()

android {
    namespace = "com.excp.podroid"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // Store large pre-compressed binary assets uncompressed. AAPT2 would
    // otherwise re-deflate them (the squashfs is already gzip'd), burning
    // minutes on a 390 MB double-compression with zero size benefit.
    aaptOptions {
        noCompress += listOf("squashfs", "img")
    }

    defaultConfig {
        applicationId = "com.excp.podroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 42
        versionName = "1.3.9"
        buildConfigField("String", "QEMU_VERSION", "\"$podroidQemuVersion\"")
        // Self-hosted update manifest (see jackbox/build-box.sh + publish-thebox.sh
        // in mazemaker-mobile). NOT GitHub: itsXactlY/Iris-Messenger is a PRIVATE
        // repo, so an unauthenticated api.github.com/repos/.../releases/latest call
        // always 404s (GitHub hides private repos from anonymous requests) — the
        // in-app updater silently never worked. This manifest is served straight
        // off mazemaker-prod, no auth needed, no GitHub rate limits to worry about.
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://iris.mazemaker.online/thebox/manifest.json\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only build for arm64-v8a — we target AArch64 Android devices exclusively
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            // Preferred: a keystore.properties file (storeFile/storePassword/
            // keyAlias/keyPassword; storeFile relative to the props file) so
            // secrets never ride the command line. Fallback: the individual
            // PODROID_RELEASE_* Gradle properties.
            val propsPath = (project.findProperty("PODROID_KEYSTORE_PROPERTIES") as? String)
            val propsFile = propsPath?.let { file(it) }
            if (propsFile != null && propsFile.exists()) {
                val p = Properties().apply { propsFile.inputStream().use { s -> load(s) } }
                storeFile     = propsFile.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias      = p.getProperty("keyAlias")
                keyPassword   = p.getProperty("keyPassword")
            } else {
                val storePath = (project.findProperty("PODROID_RELEASE_STORE_FILE") as? String)
                if (storePath != null && file(storePath).exists()) {
                    storeFile     = file(storePath)
                    storePassword = project.findProperty("PODROID_RELEASE_STORE_PASSWORD") as? String
                    keyAlias      = project.findProperty("PODROID_RELEASE_KEY_ALIAS")      as? String
                    keyPassword   = project.findProperty("PODROID_RELEASE_KEY_PASSWORD")   as? String
                }
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isJniDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Suppress Kotlin future-compat warning about annotation targets (KT-73255)
    // and silence hiltViewModel deprecation until Hilt updates its own docs.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xannotation-default-target=param-property",
                "-nowarn"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // QEMU is an ELF executable packaged as libqemu-system-aarch64.so.
        // It must be extracted to disk so ProcessBuilder can execute it.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose BOM — pins all Compose library versions
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore (app settings)
    implementation(libs.androidx.datastore.preferences)

    // Vendored Termux terminal emulator & view (MatanZ/termux-app:sixel4 — Sixel + iTerm2 image support)
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    // HiddenApiBypass — exempts our process from Android 14+ reflection filtering
    // so we can call the @SystemApi VirtualMachineManager constructors via
    // reflection on devices where the dev-grant path holds (Pixel 8+ etc.).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
