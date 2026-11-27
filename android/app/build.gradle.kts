import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}

// The google-services plugin needs app/google-services.json from the Firebase console
// (project Mabala, App ID 1:237562839144:android:234228db94a7ab9370fbdd).
// Without it the app still builds and runs in demo mode with sample data.
// -PdemoMode=true builds a sample-data APK even though google-services.json is present.
val demoMode = (project.findProperty("demoMode") as String?) == "true"
val hasGoogleServices = file("google-services.json").exists() && !demoMode

// Maps SDK for Android key (restricted to "Maps SDK for Android" + this package/SHA-1 in Cloud Console).
// Read from local.properties (MAPS_ANDROID_API_KEY=...) or the CI environment — never committed.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val mapsAndroidKey: String = (localProps.getProperty("MAPS_ANDROID_API_KEY") ?: System.getenv("MAPS_ANDROID_API_KEY") ?: "").trim()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "selo.Nachipa01"
    compileSdk = 36

    defaultConfig {
        applicationId = "selo.Nachipa01"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "6.9.4"
        vectorDrawables { useSupportLibrary = true }
        // Named Firestore database shared with the web back office
        buildConfigField("String", "FIRESTORE_DB_ID", "\"ai-studio-611c950c-febe-47c1-afff-35dcb0788339\"")
        buildConfigField("String", "FUNCTIONS_REGION", "\"us-central1\"")
        buildConfigField("String", "TRACKING_BASE_URL", "\"https://mabala-f2d65.web.app/t/\"")
        buildConfigField("String", "RTDB_URL", "\"https://mabala-f2d65-default-rtdb.firebaseio.com\"")
        buildConfigField("boolean", "HAS_FIREBASE_CONFIG", hasGoogleServices.toString())
        buildConfigField("boolean", "MAPS_ENABLED", mapsAndroidKey.isNotEmpty().toString())
        manifestPlaceholders["mapsApiKey"] = mapsAndroidKey
    }

    signingConfigs {
        // Release signing is read from environment variables in CI; never commit keystores.
        create("release") {
            val ks = System.getenv("SELO_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SELO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SELO_KEY_ALIAS")
                keyPassword = System.getenv("SELO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("SELO_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // CameraX for seller video + photo capture
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // v6.1 discovery feed: video playback, on-device labelling, image loading
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("com.google.mlkit:image-labeling:17.0.9")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("com.google.zxing:core:3.5.3") // QR code on the verified rider ID
    // Google Maps SDK for Android (map display only) + marker clustering; osmdroid is the fallback for builds without a key
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // SMS Retriever / SMS User Consent: auto-read the Beem OTP (no SMS permission needed)
    implementation("com.google.android.gms:play-services-auth-api-phone:18.1.0")
    // Fused location (on-device, free): reliable "use my location" with a GPS-on prompt
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    // Firebase BoM from the Firebase console; versions come from the BoM
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    implementation("com.google.firebase:firebase-storage")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.79.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
