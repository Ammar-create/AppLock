import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("dev.rikka.tools.refine") version "4.4.0"
}

// ---- Velvet build knobs (used by CI, harmless locally) ----
// -Pvelvet.appIdSuffix=velvet   -> dev.pranav.applock.velvet (installs side by side)
// -Pvelvet.minify=false         -> skip R8 for faster/debuggable builds
// -Pvelvet.abis=arm64-v8a       -> only ship the tablet's ABI
val velvetAppIdSuffix: String? = providers.gradleProperty("velvet.appIdSuffix").orNull
val velvetMinify: Boolean = providers.gradleProperty("velvet.minify").orNull?.toBoolean() ?: true
val velvetAbis: List<String>? = providers.gradleProperty("velvet.abis").orNull
    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
val velvetLabel: String = if (velvetAppIdSuffix.isNullOrBlank()) "@string/app_name" else "AppLock Velvet"

val ciKeystore = System.getenv("KEYSTORE_PATH")?.let { file(it) }

android {
    namespace = "dev.pranav.applock"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pranav.applock" + (velvetAppIdSuffix?.takeIf { it.isNotBlank() }?.let { ".$it" } ?: "")
        minSdk = 26
        targetSdk = 37
        versionCode = 251
        versionName = "2.5.1" + (velvetAppIdSuffix?.takeIf { it.isNotBlank() }?.let { "-velvet" } ?: "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appLabel"] = velvetLabel

        ndk {
            abiFilters += velvetAbis ?: listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (ciKeystore != null && ciKeystore.exists()) {
            create("ci") {
                storeFile = ciKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = velvetMinify
            isShrinkResources = velvetMinify

            vcsInfo.include = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (ciKeystore != null && ciKeystore.exists()) {
                signingConfigs.getByName("ci")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin.compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    buildFeatures {
        compose = true
        aidl = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":appintro"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.refine.runtime)
    compileOnly(project(":hidden-api"))
    implementation(libs.hiddenapibypass)
    implementation(project(":patternlock"))



    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
