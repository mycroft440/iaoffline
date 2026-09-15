plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.ialocal"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.example.ialocal"
        // The official llama.cpp Android binding currently targets Android 13+.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Formal syntax parsers used by the offline code editor.
    // SQL uses JSqlParser. For Tree-sitter, CI builds a local AAR with the maximum static
    // grammar set through scripts/prepare_tree_sitter_android.sh. The Maven artifact remains a
    // development fallback so Gradle sync still works before the preparation script is run.
    implementation("com.github.jsqlparser:jsqlparser:5.3") {
        exclude(group = "org.openjdk.jmh", module = "jmh-core")
    }

    // A file() AAR does not carry Maven metadata, so declare the Android Tree-sitter facade's
    // runtime/API dependencies explicitly. Keeping these declarations for the Maven fallback is
    // harmless: Gradle resolves them to one compatible version.
    implementation("io.github.tree-sitter:ktreesitter:0.25.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.22.2")

    val maximumTreeSitterAar = file("libs/tree-sitter-language-pack-android-max.aar")
    if (maximumTreeSitterAar.isFile) {
        implementation(files(maximumTreeSitterAar))
    } else {
        implementation("io.xberg.tslp.android:tree-sitter-language-pack-android:1.15.12")
    }

    // Generated from the official ggml-org/llama.cpp Android binding.
    // Run scripts/prepare_llama_android.sh before building the app.
    implementation(files("libs/llama-android.aar"))

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
