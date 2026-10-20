import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Vlastní ladicí klíč (mimo Git) – viz signingConfigs
val ks = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { this.load(it) }
}

android {
    namespace = "cz.antonin.rovergnss"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.antonin.rovergnss"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "0.8.0"
    }

    // Vlastní ladicí klíč: keystore/ v kořeni projektu (mimo Git). Na PC ho má Android Studio, na GitHubu ho
    // workflow vytvoří z GitHub Secrets – APK z obou míst jde instalovat přes sebe. Bez něj výchozí klíč Android Studia.
    signingConfigs {
        if (!ks.isEmpty) getByName("debug") {
            storeFile = rootProject.file("keystore/rovergnss-debug.keystore")
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
