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

val naverClientId = System.getenv("NAVER_CLIENT_ID")
    ?: localEnv.getProperty("NAVER_CLIENT_ID")
    ?: ""
val naverClientSecret = System.getenv("NAVER_CLIENT_SECRET")
    ?: localEnv.getProperty("NAVER_CLIENT_SECRET")
    ?: ""
val naverClientName = System.getenv("NAVER_CLIENT_NAME")
    ?: localEnv.getProperty("NAVER_CLIENT_NAME")
    ?: "DDUBI"

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
        buildConfigField("String", "NAVER_CLIENT_ID", naverClientId.asBuildConfigString())
        buildConfigField("String", "NAVER_CLIENT_SECRET", naverClientSecret.asBuildConfigString())
        buildConfigField("String", "NAVER_CLIENT_NAME", naverClientName.asBuildConfigString())
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("com.navercorp.nid:oauth:5.12.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
