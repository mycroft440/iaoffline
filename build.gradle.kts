plugins {
    id("com.android.application") version "9.4.0" apply false
    // Compose compiler version follows the Kotlin line documented by current Android setup guidance.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
