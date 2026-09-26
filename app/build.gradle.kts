plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.brigade"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brigade"
        // minSdk 30 costs nothing on the Tab S7+ (Android 12/13) and buys
        // createDisplayContext() + maximumWindowMetrics, the non-deprecated way to
        // read a display's pixel size, plus Activity.getDisplay().
        minSdk = 30
        targetSdk = 35
        // Bumped on every change that ships, patch by patch — gaps are fine and expected.
        //
        // versionCode is derived: major * 10000 + minor * 100 + patch. It only has to
        // increase, and deriving it means it can never silently disagree with the name
        // someone is reading in Settings or on a release page.
        versionCode = 600
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

dependencies {
    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.savedstate)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.snakeyaml)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
