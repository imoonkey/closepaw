// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // AGP 8.9.1+ required for Leap SDK 0.9.2 (depends on androidx.core:core-ktx:1.17.0)
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 2.3.0 required for Leap SDK 0.9.2 (kotlin-stdlib metadata version 2.3.0)
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
}
