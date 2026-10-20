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
// Until the production IDs are configured, the release uses Google's test IDs so the ads can be tried.
val useTestAds = productionAdMobAppId.isEmpty()
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

/** Formats beyond the banner. A production release shows each only when its variable is set. */
data class OptionalAdUnit(val buildConfigField: String, val productionId: String, val testId: String)

fun optionalAdUnit(buildConfigField: String, variable: String, testId: String): OptionalAdUnit {
    val productionId = providers.environmentVariable(variable).orNull?.trim().orEmpty()
    if (productionId.isNotEmpty()) {
        require(productionAdMobAppId.isNotEmpty()) { "$variable exige ADMOB_APP_ID e ADMOB_BANNER_AD_UNIT_ID." }
        require(Regex("ca-app-pub-\\d{16}/\\d{10}").matches(productionId)) {
            "$variable não é um ID de bloco de anúncios AdMob válido."
        }
    }
    return OptionalAdUnit(buildConfigField, productionId, testId)
}

val optionalAdUnits = listOf(
    optionalAdUnit("INTERSTITIAL_AD_UNIT_ID", "ADMOB_INTERSTITIAL_AD_UNIT_ID", "ca-app-pub-3940256099942544/1033173712"),
    optionalAdUnit("REWARDED_AD_UNIT_ID", "ADMOB_REWARDED_AD_UNIT_ID", "ca-app-pub-3940256099942544/5224354917"),
    optionalAdUnit("NATIVE_AD_UNIT_ID", "ADMOB_NATIVE_AD_UNIT_ID", "ca-app-pub-3940256099942544/2247696110"),
    optionalAdUnit("APP_OPEN_AD_UNIT_ID", "ADMOB_APP_OPEN_AD_UNIT_ID", "ca-app-pub-3940256099942544/9257395921"),
)

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
        // The llama.cpp binding only ships these ABIs; other libraries' 32-bit copies would make
        // the APK install on phones where the runtime cannot load.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // Debug builds use a key kept in the repository instead of the runner's ~/.android one,
        // which every Actions run generated anew: the phone then refused each new APK as an
        // update of the installed app. Its password is public, like Android's own debug key, so
        // it must never sign the published release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
            // The debug APK is the one installed on phones while the release is not signed. As a
            // debuggable app, Android runs its code with the optimizations off and ignores the
            // libraries' baseline profiles, so Compose screens stuttered while scrolling (the
            // catalog the most). Test ads, the build config and the signing key stay the debug ones.
            isDebuggable = false
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$testBannerAdUnitId\"")
            optionalAdUnits.forEach { buildConfigField("String", it.buildConfigField, "\"${it.testId}\"") }
            buildConfigField("boolean", "TEST_ADS", "true")
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            manifestPlaceholders["admobAppId"] = if (useTestAds) testAdMobAppId else productionAdMobAppId
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"${if (useTestAds) testBannerAdUnitId else productionBannerAdUnitId}\"")
            optionalAdUnits.forEach {
                buildConfigField("String", it.buildConfigField, "\"${if (useTestAds) it.testId else it.productionId}\"")
            }
            buildConfigField("boolean", "TEST_ADS", "$useTestAds")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // The model runs in its own process behind an AIDL interface (runtime/remote).
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // Keep Android's standard DEX packaging instead of forcing legacy compression.
        // A smaller APK is not worth risking OEM installation failures. Restoring this default
        // also lets the Android toolchain align the DEX code appropriately for modern devices.
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
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
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

    // Offline Wikipedia: Kiwix ZIM files compress their articles with zstd.
    implementation("com.github.luben:zstd-jni:1.5.7-20@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-20")

    // Generated from the official ggml-org/llama.cpp Android binding.
    // Run scripts/prepare_llama_android.sh before building the app.
    implementation(files("libs/llama-android.aar"))

    testImplementation("junit:junit:4.13.2")
    // android.jar only has stubs of org.json; the AI process protocol is JSON.
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
