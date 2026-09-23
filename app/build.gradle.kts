plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val ciVersionCode = providers.environmentVariable("IA_VERSION_CODE").orNull?.toIntOrNull()
val ciVersionName = providers.environmentVariable("IA_VERSION_NAME").orNull

val testAdMobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerAdUnitId = "ca-app-pub-3940256099942544/9214589741"
val productionAdMobAppId = providers.environmentVariable("ADMOB_APP_ID").orNull?.trim().orEmpty()
val productionBannerAdUnitId = providers.environmentVariable("ADMOB_BANNER_AD_UNIT_ID").orNull?.trim().orEmpty()
require(productionAdMobAppId.isEmpty() == productionBannerAdUnitId.isEmpty()) {
    "Configure ADMOB_APP_ID e ADMOB_BANNER_AD_UNIT_ID juntos."
}
if (productionAdMobAppId.isNotEmpty()) {
    require(Regex("ca-app-pub-\\d{16}~\\d{10}").matches(productionAdMobAppId)) {
        "ADMOB_APP_ID não é um ID de aplicativo AdMob válido."
    }
    require(Regex("ca-app-pub-\\d{16}/\\d{10}").matches(productionBannerAdUnitId)) {
        "ADMOB_BANNER_AD_UNIT_ID não é um ID de banner AdMob válido."
    }
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

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
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "0.1.0"
        manifestPlaceholders["admobAppId"] = testAdMobAppId
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$testBannerAdUnitId\"")
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            manifestPlaceholders["admobAppId"] = productionAdMobAppId.ifEmpty { testAdMobAppId }
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$productionBannerAdUnitId\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    // Code language packs are tiny JSON prompt guides downloaded on demand.
    // No compiler, linter or Tree-sitter grammar is embedded in the base APK.

    // Generated from the official ggml-org/llama.cpp Android binding.
    // Run scripts/prepare_llama_android.sh before building the app.
    implementation(files("libs/llama-android.aar"))

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
