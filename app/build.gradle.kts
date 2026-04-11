import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.moonkey.androidagent"
    compileSdk = 36  // Required by Leap SDK 0.9.2 (depends on androidx.core:core-ktx:1.17.0)

    defaultConfig {
        applicationId = "com.moonkey.androidagent"
        // Required by LiquidAI Leap SDK for local inference.
        // If we need to support Android < 12, consider a cloud-only flavor.
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            val evalSsl = project.findProperty("insecureSslForEval")?.toString()?.toBoolean() ?: false
            buildConfigField("boolean", "INSECURE_SSL_FOR_EVAL", evalSsl.toString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "INSECURE_SSL_FOR_EVAL", "false")
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
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
        }
    }
    
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }
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
    
    // Shizuku — binder forwarding with shell UID for virtual display
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    
    // Hidden API bypass — for InputEvent.setDisplayId(), ServiceManager access, etc.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("com.google.truth:truth:1.4.2")
    // Pure Java JSON library for unit tests (Android's JSONObject is not available in unit tests)
    testImplementation("org.json:json:20240303")
}
