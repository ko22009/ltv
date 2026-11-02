import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply { file("../local.properties").inputStream().use { load(it) } }

android {
    namespace = "ru.lazytv"
    compileSdk = 34
    defaultConfig {
        applicationId = "ru.lazytv"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("TMDB_KEY")}\"")
    }
    buildFeatures { buildConfig = true }
    // ByeDPI is run from the native library directory, so the libraries must be unpacked on install
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
}
