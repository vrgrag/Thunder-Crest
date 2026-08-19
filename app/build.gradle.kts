import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.thundercrest.thundercrestgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.thundercrest.thundercrestgame"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.1"

        buildConfigField(
            "String",
            "CONFIG_ENDPOINT",
            "\"https://thunndercrest.com/config.php\""
        )
        buildConfigField(
            "String",
            "APPSFLYER_DEV_KEY",
            "\"PG6N5qRcCdbtsBJs7vTBre\""
        )
        buildConfigField(
            "String",
            "FIREBASE_PROJECT_NUMBER",
            "\"500343001472\""
        )
        buildConfigField(
            "String",
            "SITE_URL",
            "\"https://thunndercrest.com\""
        )
        buildConfigField(
            "String",
            "PRIVACY_URL",
            "\"https://thunndercrest.com/privacy-policy.html\""
        )
        buildConfigField(
            "String",
            "SUPPORT_URL",
            "\"https://thunndercrest.com/support.html\""
        )
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
        debug {
            isMinifyEnabled = false
        }
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
        compose = true
        buildConfig = true
    }

    lint {
        // False positive: our activities extend ComponentActivity (not the old
        // FragmentActivity), so the Fragment version restriction does not apply.
        disable += "InvalidFragmentVersionForActivityResult"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Expose the root-level ridge.properties as a debug-only asset so the
    // RidgeOverrides loader can read it at runtime. The file is git-ignored,
    // so release builds simply get an empty assets folder here.
    sourceSets {
        getByName("debug") {
            assets.srcDir(layout.buildDirectory.dir("generated/ridge-assets"))
        }
    }
}

val prepareRidgeAssets by tasks.registering(Copy::class) {
    val src = rootProject.file("ridge.properties")
    onlyIf { src.exists() }
    from(src)
    into(layout.buildDirectory.dir("generated/ridge-assets"))
}

tasks.named("preBuild").configure { dependsOn(prepareRidgeAssets) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")

    // Coroutines + DataStore for persisted app mode / config state.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Networking for the config endpoint.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Firebase Cloud Messaging.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // AppsFlyer SDK + Play install referrer (required by AppsFlyer).
    implementation("com.appsflyer:af-android-sdk:6.14.2")
    implementation("com.android.installreferrer:installreferrer:2.2")
}
