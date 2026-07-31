plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun readProjectVersion(): String {
    val file = rootProject.file("../VERSION")
    require(file.exists()) { "Missing VERSION file at ${file.canonicalPath}" }
    val raw = file.readText().trim().removePrefix("v")
    require(raw.matches(Regex("""\d+\.\d+\.\d+([.-].+)?"""))) {
        "VERSION must be semver (got '$raw')"
    }
    return raw
}

fun semverToVersionCode(semver: String): Int {
    val core = semver.split("-", limit = 2)[0]
    val parts = core.split(".")
    val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
    require(major in 0..210) { "major too large for versionCode encoding" }
    require(minor in 0..99) { "minor must be 0-99 for versionCode encoding" }
    require(patch in 0..99) { "patch must be 0-99 for versionCode encoding" }
    return major * 10000 + minor * 100 + patch
}

val projectVersion = readProjectVersion()
val projectVersionCode = semverToVersionCode(projectVersion)

val debugAuthKeyHex = "e25a50de00000000000000000000000000000000000000000000000000000001"
val authKeyHex = System.getenv("EZ_SOS_AUTH_KEY")?.trim().orEmpty().ifBlank { debugAuthKeyHex }
require(authKeyHex.matches(Regex("^[0-9a-fA-F]{64}$"))) {
    "EZ_SOS_AUTH_KEY must be 64 hex chars (32 bytes); got length ${authKeyHex.length}"
}

// Release signing is driven by env vars (CI secrets). Local unsigned release is allowed.
val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank()

android {
    namespace = "com.ezsos.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ezsos.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = projectVersionCode
        versionName = projectVersion
        buildConfigField("String", "EZ_SOS_AUTH_KEY_HEX", "\"$authKeyHex\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: error("ANDROID_KEYSTORE_PASSWORD is required when ANDROID_KEYSTORE_PATH is set")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    ?: error("ANDROID_KEY_ALIAS is required when ANDROID_KEYSTORE_PATH is set")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: error("ANDROID_KEY_PASSWORD is required when ANDROID_KEYSTORE_PATH is set")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(project(":pebblekit"))
    implementation("io.rebble.pebblekit2:client:1.2.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
