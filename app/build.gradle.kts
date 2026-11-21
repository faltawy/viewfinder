plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** The app's version lives in gradle.properties (`viewfinder.version`); the release workflow checks tags against it. */
val appVersion: String = providers.gradleProperty("viewfinder.version").get()

/** 1.2.3 → 10203: two digits per part, so every release orders above the one before it. */
fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toInt() }
    require(parts.size == 3 && parts[1] < 100 && parts[2] < 100) { "viewfinder.version must be major.minor.patch, got $version" }
    return parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

/** Release signing comes from the environment (the release workflow sets it from secrets); without it, release builds are unsigned. */
val releaseKeystore: String? = providers.environmentVariable("VIEWFINDER_KEYSTORE").orNull

android {
    namespace = "app.viewfinder"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.viewfinder"
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("VIEWFINDER_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("VIEWFINDER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("VIEWFINDER_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":ptp"))
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.haze)
    implementation(libs.exifinterface)
    implementation(libs.heifwriter)
    testImplementation(libs.junit)
    debugImplementation(libs.compose.tooling)
}
