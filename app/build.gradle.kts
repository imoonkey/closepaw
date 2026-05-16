import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Generates open_source_licenses.{json,html} from runtime deps; copies the
    // JSON into src/main/assets so the in-app "Open Source Licenses" page can
    // render the live dependency graph at runtime.
    id("com.jaredsburrows.license") version "0.9.8"
}

android {
    namespace = "ai.closepaw"
    compileSdk = 36  // Required by Leap SDK 0.9.2 (depends on androidx.core:core-ktx:1.17.0)

    defaultConfig {
        applicationId = "ai.closepaw"
        // Required by LiquidAI Leap SDK for local inference.
        // If we need to support Android < 12, consider a cloud-only flavor.
        minSdk = 31
        targetSdk = 36
        versionCode = (project.findProperty("VERSION_CODE") as String).toInt()
        versionName = project.findProperty("VERSION_NAME") as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads from environment so the keystore + password never
    // touch the repo. `scripts/release-build.sh` wires these from
    // ~/secrets/closepaw/. If env is unset (e.g. local debug builds, IDE sync),
    // we fall back to null and the release variant simply won't be signed —
    // debug builds use Android's default debug keystore and are unaffected.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "closepaw"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            val evalSsl = project.findProperty("insecureSslForEval")?.toString()?.toBoolean() ?: false
            buildConfigField("boolean", "INSECURE_SSL_FOR_EVAL", evalSsl.toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "INSECURE_SSL_FOR_EVAL", "false")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true  // Required for Shizuku AIDL
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    
    testOptions {
        animationsDisabled = true
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // Default test JVM heap (512m) OOMs once the suite includes Robolectric +
                // bundled Conscrypt/BouncyCastle + the OpenAI SDK + MockWebServer harnesses.
                it.maxHeapSize = "2g"
            }
        }
    }
}

val copyClosePawBridge by tasks.registering(Copy::class) {
    val bridgeSource = rootProject.layout.projectDirectory.file("tools/termux-bridge/closepaw_bridge.py")
    val rawResourceDir = layout.projectDirectory.dir("src/main/res/raw")

    from(bridgeSource)
    into(rawResourceDir)
    rename { "closepaw_bridge_py" }
    outputs.upToDateWhen { false }
}

tasks.named("preBuild") {
    dependsOn(copyClosePawBridge)
}

// gradle-license-plugin config: emit JSON only and have the plugin copy it
// into src/main/assets/open_source_licenses.json so OpenSourceLicensesPage can
// load it via AssetManager. HTML is a noisy artifact in version control; the
// JSON drives a native Compose list instead of a WebView.
licenseReport {
    generateJsonReport = true
    generateHtmlReport = false
    generateCsvReport = false
    generateTextReport = false
    copyJsonReportToAssets = true
}

// Surface the license JSON to debug builds too. The plugin auto-wires
// `licenseReleaseReport` into the release `assets` task, but debug needs an
// explicit hook so the in-app page works in regular `assembleDebug` builds.
//
// gradle-license-plugin 0.9.8 calls `Task.project` at execution time, which
// is incompatible with Gradle's configuration cache and causes the report
// to silently emit `[]` (asset wiped) when config cache is active. Mark the
// tasks as not-compatible so Gradle falls back to a non-cached execution
// just for them — the rest of the build keeps using the cache.
afterEvaluate {
    listOf("licenseDebugReport", "licenseReleaseReport").forEach { name ->
        tasks.findByName(name)?.notCompatibleWithConfigurationCache(
            "gradle-license-plugin 0.9.8 uses Task.project at execution time"
        )
    }
    tasks.findByName("mergeDebugAssets")?.dependsOn("licenseDebugReport")

    // Kotlin 2.3.0's `produceReleaseComposeMapping` ships an older ASM that
    // can't read class file major version 69 (Java 25). bcprov-jdk18on:1.84
    // bundles `META-INF/versions/25/*.class` in its multi-release jar, which
    // crashes the mapping task. The mapping file is debug-only metadata for
    // Compose-aware stack traces — skipping the whole pipeline (produce →
    // merge → report) doesn't affect APK contents.
    listOf(
        "produceReleaseComposeMapping",
        "mergeReleaseComposeMapping",
        "reportReleaseComposeMappingErrors",
    ).forEach { tasks.findByName(it)?.enabled = false }
}

// Kotlin 2.3.0 compilerOptions DSL (replaces deprecated kotlinOptions)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Compose BOM - manages all Compose library versions
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    
    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")

    // Material 3
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Lucide icons
    implementation("com.composables:icons-lucide-cmp:2.2.1")

    // Activity Compose integration
    implementation("androidx.activity:activity-compose:1.9.3")
    
    // Debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    
    // OpenAI SDK
    implementation("com.openai:openai-java:4.14.0")

    // OkHttp — used by CodexResponseClient for raw SSE streaming to chatgpt.com
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // LiquidAI Leap SDK for local LLM inference
    // Version 0.9.2 includes manifest.LeapDownloader with loadModel(modelSlug, quantizationSlug) API
    implementation("ai.liquid.leap:leap-sdk:0.9.2")
    
    // Kotlin Serialization for session persistence
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // YAML frontmatter parsing for Agent Skills
    implementation("org.yaml:snakeyaml:2.2")
    
    // Shizuku — binder forwarding with shell UID for virtual display
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    
    // Hidden API bypass — for InputEvent.setDisplayId(), ServiceManager access, etc.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")

    // BouncyCastle — X.509 self-signed cert generation for wireless ADB pairing
    // (sun.security.x509 is not available on Android). "jdk18on" = JDK 1.8 onwards.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.84")
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")

    // SPAKE2-25519 — required for ADB pairing protocol (AOSP uses BoringSSL spake25519).
    // Pure-Kotlin implementation in `wireless/Spake25519.kt` over `net.i2p.crypto:eddsa`
    // (CC0 / public domain, 25k+ Maven Central dependents) for Ed25519 group arithmetic.
    // Replaces the previous LGPL-3.0 JitPack dep `com.github.MuntashirAkon.spake2-java:spake2-android`.
    // See `projects/active/browser/cn/diag_20260504_spake_alternatives.md`.
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // Conscrypt — bundled (vs reflection on platform) for the TLS exporter API used by the
    // ADB pairing handshake. Platform Conscrypt is hidden API and HiddenApiBypass falls flat
    // on certain Android builds; the bundled AAR (~3MB) gives us a stable Conscrypt.exportKeyingMaterial.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.google.truth:truth:1.4.2")
    // Pure Java JSON library for unit tests (Android's JSONObject is not available in unit tests)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.2.1")

    // Instrumented QA tests (Compose UI Test on emulator/device)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("io.mockk:mockk-android:1.13.9")
}
