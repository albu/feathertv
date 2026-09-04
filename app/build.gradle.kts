import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Local-only secrets (gitignored). TMDB_API_KEY is read from secrets.properties
// so the API key never ends up in the repository.
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val tmdbApiKey: String = secrets.getProperty("TMDB_API_KEY", "").trim()
val wizIp: String = secrets.getProperty("WIZ_IP", "192.168.1.100").trim()
val wizMac: String = secrets.getProperty("WIZ_MAC", "").trim()
val searchRegion: String = secrets.getProperty("SEARCH_REGION", "US").trim()
val circadianLat: String = secrets.getProperty("CIRCADIAN_LAT", "51.5074").trim()
val circadianLon: String = secrets.getProperty("CIRCADIAN_LON", "-0.1278").trim()

android {
    namespace = "com.feathertv.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.feathertv.launcher"
        minSdk = 21 // Android 5.0+ (supports all Android TV 9/10/11/12/14 devices)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEFAULT_WIZ_IP", "\"${wizIp.replace("\"", "\\\"")}\"")
        buildConfigField("String", "WIZ_MAC", "\"${wizMac.replace("\"", "\\\"")}\"")
        buildConfigField("String", "DEFAULT_SEARCH_REGION", "\"${searchRegion.replace("\"", "\\\"")}\"")
        buildConfigField("String", "CIRCADIAN_LAT", "\"${circadianLat.replace("\"", "\\\"")}\"")
        buildConfigField("String", "CIRCADIAN_LON", "\"${circadianLon.replace("\"", "\\\"")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sign with the debug key so the release APK is sideload-installable
            // on your own TV. For public distribution, replace with a real key.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.palette)
}
