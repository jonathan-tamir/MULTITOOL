plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jonathan.multitool.core"
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
    api(platform("androidx.compose:compose-bom:2024.05.00"))
    api("androidx.compose.runtime:runtime")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.core:core-ktx:1.13.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
