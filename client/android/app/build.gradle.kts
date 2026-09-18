import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// The Firebase config is intentionally local-only. Keep non-FCM builds usable
// when the file has not been provisioned yet.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

val localEnv = Properties().apply {
    val envFile = rootProject.file("../.env")
    if (envFile.exists()) {
        envFile.inputStream().use(::load)
    }
}

val kakaoNativeAppKey = System.getenv("KAKAO_NATIVE_APP_KEY")
    ?: localEnv.getProperty("KAKAO_NATIVE_APP_KEY")
    ?: "NOT_CONFIGURED"

val ddubiKeystorePath = System.getenv("DDUBI_KEYSTORE_PATH")
val ddubiKeystorePassword = System.getenv("DDUBI_KEYSTORE_PASSWORD")
val ddubiKeyAlias = System.getenv("DDUBI_KEY_ALIAS") ?: "ddubi"
val ddubiKeyPassword = System.getenv("DDUBI_KEY_PASSWORD")
val ddubiKeystoreFile = ddubiKeystorePath
    ?.takeIf { it.isNotBlank() }
    ?.let(::File)
val releaseSigningConfigured = listOf(
    ddubiKeystorePassword,
    ddubiKeyPassword,
).all { !it.isNullOrBlank() } && ddubiKeystoreFile?.isFile == true
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}

if (releaseTaskRequested && !releaseSigningConfigured) {
    throw GradleException(
        "Release signing is not configured. Set DDUBI_KEYSTORE_PATH, " +
            "DDUBI_KEYSTORE_PASSWORD, and DDUBI_KEY_PASSWORD.",
    )
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.ssafy.ddubi"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = ddubiKeystoreFile
                storePassword = ddubiKeystorePassword
                keyAlias = ddubiKeyAlias
                keyPassword = ddubiKeyPassword
            }
        }
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.ssafy.ddubi"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["KAKAO_NATIVE_APP_KEY"] = kakaoNativeAppKey
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                // IDE model sync and debug builds remain usable, while an actual
                // release task is rejected by the guard above.
                signingConfigs.getByName("debug")
            }
            // R8 renames the Kakao map and Naver login classes that those SDKs look up
            // by name at runtime, which aborts the release app. Keep rules live in
            // proguard-rules.pro; without this line they are never applied.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
