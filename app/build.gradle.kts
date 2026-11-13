import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply { file("../local.properties").inputStream().use { load(it) } }

android {
    namespace = "ru.lazytv"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.lazytv"
        minSdk = 21
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.2"
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("TMDB_KEY")}\"")
        // Google OAuth client of the sync (docs/sync.md): empty keys switch the sync section off
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${localProps.getProperty("DRIVE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${localProps.getProperty("DRIVE_CLIENT_SECRET") ?: ""}\"")
    }
    buildTypes {
        release {
            // smaller and faster to start; the rules keep what is found by name at run time
            isMinifyEnabled = true
            isShrinkResources = true
            // the app is installed from a file, not from a store: the debug key lets this build be installed and
            // updated over the older one
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    // ByeDPI is run from the native library directory, so the libraries must be unpacked on install
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libciadpi.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // the merge of the sync is checked on the desk, without a device (app/src/test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
