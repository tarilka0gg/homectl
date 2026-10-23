plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.tarilka0gg.homectl"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.tarilka0gg.homectl"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "0.18.4"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Releases are signed with a key you provide: HOMECTL_KEYSTORE=/path/to.jks (+ HOMECTL_KEYSTORE_PASS, HOMECTL_KEY_ALIAS,
    // HOMECTL_KEY_PASS). Without it the build is signed with the standard Android debug key, which is enough to install and test.
    // (The official releases are re-signed afterwards with packaging/sign-release.sh.)
    val keystore = System.getenv("HOMECTL_KEYSTORE")
    if (keystore != null) {
        signingConfigs {
            create("homectl") {
                storeFile = file(keystore)
                storePassword = System.getenv("HOMECTL_KEYSTORE_PASS") ?: "android"
                keyAlias = System.getenv("HOMECTL_KEY_ALIAS") ?: "homectl"
                keyPassword = System.getenv("HOMECTL_KEY_PASS") ?: System.getenv("HOMECTL_KEYSTORE_PASS") ?: "android"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("homectl") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.biometric:biometric:1.1.0") // fingerprint / face / device PIN prompt for the app lock
    implementation("androidx.profileinstaller:profileinstaller:1.3.1") // installs the baseline profile on sideloaded APKs
}
