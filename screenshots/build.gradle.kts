plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi") version "1.3.4"
}

android {
    namespace = "com.jonathan.multitool.screens"
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
    implementation(project(":core"))
    implementation(project(":core-ui"))
    implementation(project(":shell"))
    testImplementation("junit:junit:4.13.2")
}
