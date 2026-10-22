plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.trainingskompass.wear"
    compileSdk = 35

    defaultConfig {
        // Gleiche applicationId wie die Handy-App, sonst finden sich die beiden nicht.
        applicationId = "ch.trainingskompass"
        minSdk = 30
        targetSdk = 34
        // Jeder CI-Build bekommt eine höhere Versionsnummer, damit Updates sauber über die alte Version gehen
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    buildFeatures { compose = true; buildConfig = true }
    // Fester Debug-Schlüssel im Repo: jede Version installiert sich über die vorige,
    // und Handy- und Uhr-App haben dieselbe Signatur. Für den Play Store braucht es einen eigenen Schlüssel.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Galaxy Watch / Wear OS: Training mit Puls und Kalorien, laufende Aktivität, Always-on, Kachel, Komplikation
    implementation("androidx.health:health-services-client:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
}
