import java.util.Properties

// ── Read local.properties (never committed to git) ───────────────────────────
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

fun localProp(key: String, default: String = ""): String =
    localProps.getProperty(key, default)

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.androidmcptelegram"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.androidmcptelegram"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Inject credentials into BuildConfig at compile time.
        // They land in bytecode, not in the resource table.
        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${localProp("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("String", "TELEGRAM_CHAT_ID",   "\"${localProp("TELEGRAM_CHAT_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true          // R8 obfuscation hides field names
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
}
