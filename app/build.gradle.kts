import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing material lives in local.properties, which is not committed. A build on a
// machine without it still works — it just produces an unsigned release.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = localProps.getProperty("RELEASE_STORE_FILE")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.scrtrans"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scrtrans"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // ML Kit's libtranslate_jni.so ships for four ABIs and accounts for 60 of the
    // universal APK's 70MB. Splitting gets a phone build down to ~20MB. x86 variants
    // are emulator-only for phones, so they are dropped; the universal APK stays as a
    // fallback for anything unusual.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(localProps.getProperty("RELEASE_STORE_FILE"))
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off. The app is small, ML Kit ships its own consumer rules, and
            // an obfuscated stack trace from a device is harder to act on than the size
            // saving is worth here.
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The only dependency. On-device ja->ko.
    implementation("com.google.mlkit:translate:17.0.3")

    // Probe only: measuring whether an on-device LLM is worth adding beside the engine.
    // Same runtime Edge Gallery loads, so its numbers and ours are comparable.
    // Remove with GemmaProbe if the answer is no.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
}
