plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.localfly"

    // Usa la sintaxis estándar para compileSdk (número entero)
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.localfly"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Deshabilitar minify para desarrollo (opcional)
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // HABILITAR VIEW BINDING (necesario para HomeFragment)
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Dependencias principales (usando libs del catálogo)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // ---- Dependencias para servidor mirepo ----
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media:media:1.7.0")

    // ML Kit para traducción de letras
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")
    
    // Google AI (Gemini) SDK
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // Waveform SeekBar y Amplituda para visualización
    implementation("com.github.massoudss:waveformSeekBar:5.0.2")
    implementation("com.github.lincollincol:amplituda:2.2.2")
}