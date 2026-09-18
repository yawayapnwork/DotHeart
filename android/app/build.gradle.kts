import java.io.FileInputStream
import java.util.Properties

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

    // ------------------------------------------------------------------
    // Release signing. Credentials come from EITHER android/keystore.properties
    // (a local, gitignored file - see android/keystore.properties.example)
    // OR the four DOTHEART_KEYSTORE_* / DOTHEART_KEY_* environment variables
    // (for CI), in that order. Exact setup: RELEASE.md.
    //
    // Deliberately does NOT hard-fail the Gradle configuration phase if
    // credentials are absent - a fresh clone with no keystore must still be
    // able to run assembleDebug, lint, or even assembleRelease for a CI
    // compile-only check. Instead, releaseSigningReady (below) gates
    // whether the release buildType actually attaches this signingConfig;
    // see that buildType's comment for what happens when it's false.
    // ------------------------------------------------------------------
    val keystoreProperties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
    }

    fun resolveSigningValue(propertyKey: String, envVarName: String): String? =
        keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envVarName)?.takeIf { it.isNotBlank() }

    val releaseStoreFilePath = resolveSigningValue("storeFile", "DOTHEART_KEYSTORE_PATH")
    val releaseStorePassword = resolveSigningValue("storePassword", "DOTHEART_KEYSTORE_PASSWORD")
    val releaseKeyAlias = resolveSigningValue("keyAlias", "DOTHEART_KEY_ALIAS")
    val releaseKeyPassword = resolveSigningValue("keyPassword", "DOTHEART_KEY_PASSWORD")

    val releaseSigningReady = listOf(
        releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
    ).all { !it.isNullOrBlank() }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
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
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No keystore configured: assembleRelease still succeeds
                // (so CI compile-only checks and a fresh clone aren't
                // blocked), but the output APK is UNSIGNED. Gradle prints
                // its own warning about this at build time. Before
                // distributing an APK, verify it is actually signed - see
                // RELEASE.md's "verify the signature" step; do not assume
                // a successful build means a signed, installable-by-
                // someone-else artifact.
                project.logger.warn(
                    "release signingConfig not set: no keystore.properties or " +
                        "DOTHEART_KEYSTORE_* env vars found. assembleRelease will " +
                        "produce an UNSIGNED APK. See RELEASE.md."
                )
            }
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
