import org.gradle.api.GradleException
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Секреты подписи release. В репозиторий не попадают.
 * Приоритет: переменные окружения, затем ~/.freemax-release.env (KEY=VALUE).
 *
 * Ключи: FREEMAX_KEYSTORE_FILE, FREEMAX_KEYSTORE_PASSWORD,
 * FREEMAX_KEY_ALIAS, FREEMAX_KEY_PASSWORD. См. docs/release.md.
 */
fun releaseSecret(name: String): String? {
    System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
    val envFile = File(System.getProperty("user.home"), ".freemax-release.env")
    if (!envFile.exists()) return null
    return envFile.readLines().mapNotNull { line ->
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || !t.contains('=')) null
        else t.substringBefore('=').trim() to t.substringAfter('=').trim()
    }.toMap()[name]?.takeIf { it.isNotBlank() }
}

val releaseStorePath = releaseSecret("FREEMAX_KEYSTORE_FILE")
val releaseStorePassword = releaseSecret("FREEMAX_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("FREEMAX_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("FREEMAX_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningReady = releaseStorePath != null && releaseStorePassword != null && releaseKeyAlias != null

// Понятная ошибка вместо «Keystore file not set», если собирают release без ключа.
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.equals("build", ignoreCase = true) ||
        it.contains("release", ignoreCase = true) ||
        it.contains("bundle", ignoreCase = true)
}
if (releaseBuildRequested && !releaseSigningReady) {
    throw GradleException(
        "Нет секретов подписи release. Заполните ~/.freemax-release.env " +
            "(FREEMAX_KEYSTORE_FILE, FREEMAX_KEYSTORE_PASSWORD, FREEMAX_KEY_ALIAS) " +
            "или задайте переменные окружения. Инструкция: docs/release.md"
    )
}

android {
    namespace = "com.freemax.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.freemax.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.5"

        // Optional speech-to-text (owner-only feature). Empty = disabled.
        // Values live in local.properties (never committed) or can be passed:
        //   ./gradlew assembleRelease -PFREEMAX_STT_API_KEY= -PFREEMAX_STT_ENDPOINT=
        fun buildSecret(name: String): String = (try {
            providers.gradleProperty(name).orNull
        } catch (_: Exception) {
            null
        }) ?: try {
            val f = rootProject.file("local.properties")
            if (f.exists()) {
                f.readLines().mapNotNull { line ->
                    val t = line.trim()
                    if (t.startsWith("$name=")) t.substringAfter("=").trim() else null
                }.firstOrNull() ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
        fun buildConfigString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val sttKey = buildSecret("FREEMAX_STT_API_KEY")
        val sttEndpoint = buildSecret("FREEMAX_STT_ENDPOINT")
        buildConfigField("String", "STT_API_KEY", buildConfigString(sttKey))
        buildConfigField("String", "STT_ENDPOINT", buildConfigString(sttEndpoint))
    }

    // Release-подпись: параметры берутся из env / ~/.freemax-release.env,
    // сам ключ и пароли в репозитории не лежат (см. docs/release.md).
    signingConfigs {
        create("release") {
            val storePath = releaseStorePath
            val storePass = releaseStorePassword
            val alias = releaseKeyAlias
            if (storePath != null && storePass != null && alias != null) {
                storeFile = File(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // AGP 8.7 + Kotlin 2.1: this lifecycle detector crashes lint with
        // "Found class ... but interface was expected". Not a project issue.
        disable += "NullSafeMutableLiveData"
    }
}

dependencies {
    implementation(project(":freemax-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.security.crypto)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.compose.icons.extended)
    implementation(libs.androidx.webkit)
    // GeckoView нужен только экрану входа через сайт, который живёт в src/debug.
    // В release движок не попадает в APK: минус ~700 МБ нативных библиотек.
    debugImplementation(libs.geckoview)

    testImplementation(libs.junit)

    debugImplementation(libs.compose.ui.tooling)
}
