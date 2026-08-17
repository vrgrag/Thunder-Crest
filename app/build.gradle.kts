import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

if (rootProject.file("app/google-services.json").exists()) {
    pluginManager.apply("com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val ridgePropertiesFile = rootProject.file("ridge.properties")
val ridgeProperties = Properties()
if (ridgePropertiesFile.exists()) {
    ridgeProperties.load(FileInputStream(ridgePropertiesFile))
}

fun ridgeString(name: String): String = ridgeProperties.getProperty(name).orEmpty()

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun ridgeBoolean(name: String, defaultValue: Boolean): Boolean =
    ridgeProperties.getProperty(name)?.toBooleanStrictOrNull() ?: defaultValue

android {
    namespace = "com.thundercrest.thundercrestgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thundercrest.thundercrestgame"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        buildConfigField("String", "PROBE_LINK", buildConfigString(ridgeString("probeLink")))
        buildConfigField("String", "FORCE_AF_STATUS", buildConfigString(ridgeString("forceAfStatus")))
        buildConfigField("String", "FORCE_PARAMS", buildConfigString(ridgeString("forceParams")))
        buildConfigField("Boolean", "STICKY_VERDICT", ridgeBoolean("stickyVerdict", true).toString())
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.webkit)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.appsflyer)
    implementation(libs.installreferrer)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
