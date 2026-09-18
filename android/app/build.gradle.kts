plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dotheart.widget"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dotheart.widget"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Overridden per build type below. This default is the production
        // Render URL; keep it in sync with the deployed backend. Configurable
        // per-machine without editing this file via:
        //   ./gradlew assembleDebug -PdotheartBaseUrl=http://192.168.1.20:8000
        buildConfigField(
            "String",
            "DOTHEART_BASE_URL",
            "\"${project.findProperty("dotheartBaseUrl") ?: "https://dotheart.onrender.com"}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // 10.0.2.2 is the emulator's alias for the host machine's
            // loopback address, for pointing at a locally running backend
            // over plain HTTP - see src/debug/AndroidManifest.xml and
            // res/xml/network_security_config_debug.xml, which permit
            // cleartext traffic ONLY to this host, localhost, and 127.0.0.1.
            // Override per-machine (e.g. a physical device on the same LAN
            // instead of the emulator loopback alias) with:
            //   ./gradlew assembleDebug -PdotheartBaseUrl=http://192.168.1.20:8000
            buildConfigField(
                "String",
                "DOTHEART_BASE_URL",
                "\"${project.findProperty("dotheartBaseUrl") ?: "http://10.0.2.2:8000"}\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Deliberately minimal dependency graph to protect the <15MB APK
    // budget: no image-loading library, no JSON library beyond the
    // platform's built-in org.json, no Play Services.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
