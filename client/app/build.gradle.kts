plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

fun signingValue(property: String, env: String): String? {
    System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return keystoreProperties.getProperty(property)?.trim()?.takeIf { it.isNotEmpty() }
}

android {
    namespace = "tv.coog.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.coog.app"
        minSdk = 23 // Compose for TV is 21; Media3 HLS requires 23. Google TV is well above this.
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.17"
        buildConfigField("String", "GITHUB_REPO", "\"teguva/coog\"")
    }

    val releaseStoreFile = signingValue("storeFile", "COOG_STORE_FILE")
    val canSignRelease = releaseStoreFile != null &&
        signingValue("storePassword", "COOG_STORE_PASSWORD") != null &&
        signingValue("keyAlias", "COOG_KEY_ALIAS") != null &&
        signingValue("keyPassword", "COOG_KEY_PASSWORD") != null

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = signingValue("storePassword", "COOG_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "COOG_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "COOG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui-compose:1.11.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
