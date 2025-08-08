plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.gms.google.services)
    id("com.google.devtools.ksp")}


android {
    namespace = "com.example.team_talk_kotlin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.team_talk_kotlin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.firebase.database)
    implementation(libs.androidx.room.gradle.plugin)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

//    Added

    // 🔐 Firebase Auth (optional)
    implementation("com.google.firebase:firebase-auth-ktx")

    // 🔄 Coroutines for Firebase tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.4")

    // 🌐 OkHttp for network requests
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 📦 JSON support (optional, but useful)
//    implementation("org.json:json:20230227")

    implementation("com.jakewharton.threetenabp:threetenabp:1.4.5")

    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)

    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("androidx.datastore:datastore-preferences:1.0.0") // Instead of SharedPreferences

    // Firebase Realtime Database
    implementation("com.google.firebase:firebase-database-ktx:20.3.0")

    // Firebase Cloud Messaging
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.1")

    // Kotlin Coroutines with Firebase
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    val firebaseBom = platform("com.google.firebase:firebase-bom:32.8.1")
    implementation(firebaseBom)

// Firebase libraries (no version needed when using BOM)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("io.github.webrtc-sdk:android:125.6422.07")

    implementation("androidx.compose.material:material-icons-extended:1.6.1") // or latest version

    implementation("com.google.firebase:firebase-database-ktx:20.3.0")

    implementation("io.livekit:livekit-android-compose-components:1.4.0")

    implementation("io.livekit:livekit-android:2.18.3")

    val room_version = "2.7.2"

    ksp("androidx.room:room-compiler:$room_version")

    implementation("androidx.room:room-ktx:${room_version}")

    val livekit_version = "2.18.3"

    implementation("io.livekit:livekit-android:$livekit_version")

    // CameraX support with pinch to zoom, torch control, etc.
    implementation("io.livekit:livekit-android-camerax:$livekit_version")

    // Track processors, such as virtual background
    implementation("io.livekit:livekit-android-track-processors:$livekit_version")
}