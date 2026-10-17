plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kanso.spending"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kanso.spending"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "2.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

// --- Room schema-export robustness -------------------------------------------
// Room refuses to deserialize a zero-byte schema JSON ("Empty schema file"),
// which an interrupted/partial write (e.g. a cancelled CI job) or a restored
// build-cache artifact can leave behind, failing the entire build for a file it
// only reads for reference. The schema directory is *generated output*, never a
// source, so purge any empty schema before KSP reads the directory: a stale,
// truncated artifact must never be able to break the build.
val roomSchemaExportDir = file("$projectDir/schemas")
tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }.configureEach {
    val schemaDir = roomSchemaExportDir
    doFirst {
        if (schemaDir.isDirectory) {
            schemaDir.walkTopDown()
                .filter { it.isFile && it.length() == 0L }
                .forEach { it.delete() }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // 100% On-Device SQLite Persistence via AndroidX Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    // Optional biometric app lock (privacy screen — not data encryption).
    implementation(libs.androidx.biometric)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for unit tests: android.jar's org.json is a "not mocked"
    // stub, but AiResponseParser parses real JSON in JVM tests.
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
