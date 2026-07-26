plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonathan.multitool.ui"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    api(project(":core"))
    api(platform("androidx.compose:compose-bom:2024.05.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-text-google-fonts")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    api("androidx.activity:activity-compose:1.9.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
