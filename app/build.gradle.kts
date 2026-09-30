import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.prasbin.shadowlearn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.prasbin.shadowlearn"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase1"
        // Phase 14: ship only the ABIs that matter — arm64-v8a (real
        // devices incl. the Redmi Note 14 5G target) and x86_64 (CE_Test
        // emulator). 32-bit devices keep every other feature; STT on them
        // reports the honest unavailable-engine reason. Saves ~19 MB.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
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
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    // Room schema exports are committed (app/schemas/) to enable future migration tests.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric resolves its android-all runtime through its own Maven
// resolver (not Gradle's). Point it at a cache under GRADLE_USER_HOME:
// robolectric.dependency.dir is a FLAT directory of jars, so the runtime
// lives directly at <dir>/android-all-instrumented-<ver>.jar (pre-seeded;
// see docs/SETUP.md). Offline mode avoids the network entirely.
val robolectricDepsDir =
    file("${System.getenv("GRADLE_USER_HOME") ?: "${System.getProperty("user.home")}/.gradle"}/robolectric-deps")
tasks.withType<Test> {
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.absolutePath)
    // Offline: resolve the android-all runtime from the seeded cache only.
    // (repo1.maven.org is unusably slow from this network; the runtime is
    // pre-seeded under GRADLE_USER_HOME/robolectric-deps — see docs/SETUP.md.)
    systemProperty("robolectric.offline", "true")
}

val roomVersion = "2.8.3"

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Foundation for later phases (no workers scheduled yet — Phase 2+).
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Phase 14: Vosk offline speech recognition (Apache-2.0). Native
    // libvosk.so ships for arm64-v8a + x86_64 (see abiFilters above);
    // the 41 MB small-en-US model ships under src/main/assets and is
    // unpacked to app-private storage on first transcription — never
    // downloaded, never sent anywhere.
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Real FTS5 module for unit tests (already present in the offline cache).
    // Production code never links it; only test classes use it.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
