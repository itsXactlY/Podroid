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
        // 49 / 1.5.0: the guest's agent is Ariadne (native C++, one static
        // binary at /opt/ariadne) instead of the Python Daedalus venv; migration
        // 49.sh carries the app's api_server.key across.
        // 48 / 1.4.5: vendored iris image = gateway 4945474 (one in-flight per peer,
        // user mail only to the hosting gateway). The in-app updater is gone — The Box (dev.mazemaker.thebox)
        // is the family's only updater. No REQUEST_INSTALL_PACKAGES any more.
        // 47 / 1.4.4: vendored image bumped to gateway.py 785d34f — a peer that
        // re-keys (reset, reinstall, simultaneous first-contact) is followed
        // instead of being dropped forever; parallel Tor outbox + replay-guard
        // fixes from 1.4.3's window. Same rootfs otherwise.
        // 46 / 1.4.3: the iris pod federates over Tor — the vendored image is the
        // single binary with tor inside (FROM scratch), iris-pod-start.sh runs
        // IRIS_RELAY_TRANSPORT=tor. Rootfs otherwise identical to 1.4.2.
        // 45 / 1.4.2: the guest rootfs changed (Daedalus 0.8.1 -> 0.8.2, and
        // the curated skills went from 0 to 100 once build-daedalus-venv.sh
        // started copying skills/ into the agent HOME). Rebuilding the same
        // versionCode would have shipped a materially different app under a
        // number identical to the one already installed — invisible to the
        // in-app updater, and indistinguishable from "nothing happened".
        // 50 / 1.5.1: Ariadne rebuilt from main (8a58f98) -- the musl build fixes (fcntl.h, the
        // fatal-signal handler), the gateway's adopt names mazemaker-gateway; Hermes is retired
        // across the stack. Same versionCode would hide the new rootfs from The Box.
        // 51 / 1.5.2: the guest's agent no longer dies in restart loops. mcp-mazemaker-watch.sh
        // clobbered its own loop flag (sh has no locals) and restarted Ariadne every poll while
        // the app's proxy was up; podroid-ariadne's stop did not wait (no retry), so a restart
        // during startup left the service "crashed" with nothing to bring it back. The watcher
        // now recovers a crashed agent, and the stop waits (TERM/15/KILL/5).
        // 52 / 1.5.3: the watcher restarts Ariadne only when it lacks the proxy. One slow probe
        // no longer counts as "proxy down" (2 in a row do), an Ariadne that had the proxy is not
        // restarted when it returns, and one whose MCP connect failed while the proxy answers
        // is restarted (at most every 2 min) instead of staying at 11 tools.
        // 53 / 1.5.4: the guest carries a current Iris image (tor-capable binary with the
        // chat-column UI fix) instead of the July source image, which crash-looped on the
        // start script's IRIS_RELAY_TRANSPORT=tor, and Ariadne at main.
        // 54 / 1.5.5: the guest's Iris image is Iris main 727de3a. Browser gateway chats no
        // longer lose or fork their session (an envelope is taken once, a reader that cannot
        // decrypt re-handshakes by itself, a burst no longer erases ratchet state), accepted
        // mail survives a pod restart (sqlite store), mail for an unpinned peer stops being
        // retried forever, and the arm64 tor bundle carries lyrebird (bridges on phones).
        versionCode = 54
        versionName = "1.5.5"
        buildConfigField("String", "QEMU_VERSION", "\"$podroidQemuVersion\"")

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
            // Naming a props file that does not exist is always a mistake --
            // a typo'd path, or a placeholder pasted verbatim. Silently falling
            // through to the PODROID_RELEASE_* branch (which is also unset, so
            // the whole config stays empty) buries that in a packageRelease
            // failure three minutes later reading
            //     SigningConfig "release" is missing required property "storeFile"
            // which names neither the property that was passed nor the path it
            // could not find. Fail here, where the cause is still visible.
            if (propsPath != null && (propsFile == null || !propsFile.exists())) {
                throw GradleException(
                    "PODROID_KEYSTORE_PROPERTIES points at a file that does not exist: $propsPath"
                )
            }
            if (propsFile != null && propsFile.exists()) {
                val p = Properties().apply { propsFile.inputStream().use { s -> load(s) } }
                storeFile     = propsFile.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias      = p.getProperty("keyAlias")
                keyPassword   = p.getProperty("keyPassword")
            } else {
                val storePath = (project.findProperty("PODROID_RELEASE_STORE_FILE") as? String)
                if (storePath != null && !file(storePath).exists()) {
                    throw GradleException(
                        "PODROID_RELEASE_STORE_FILE points at a file that does not exist: $storePath"
                    )
                }
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
