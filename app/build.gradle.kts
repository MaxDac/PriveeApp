import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Release signing comes from keystore.properties (local) or environment variables (CI).
// Without them the release APK is unsigned, as F-Droid signs its own builds.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun signingValue(property: String, env: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "PRIVEE_KEYSTORE_FILE")

android {
    namespace = "com.privee.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.privee.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "PRIVEE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PRIVEE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PRIVEE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Debug builds may talk to a development server over plain HTTP.
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            // Suggested (never preselected) on the server screen: the host machine, as seen from the emulator.
            buildConfigField("String", "DEV_SERVER_SUGGESTION", "\"http://10.0.2.2:4000\"")
            // Server of the pre-existing account and keys, before servers were configurable; only used to migrate them.
            buildConfigField("String", "LEGACY_SERVER_URL", "\"http://10.0.2.2:4000\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            buildConfigField("String", "DEV_SERVER_SUGGESTION", "\"\"")
            buildConfigField("String", "LEGACY_SERVER_URL", "\"https://privee.fly.dev\"")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Reproducible builds: no Google-encrypted dependency metadata (F-Droid).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        // libsignal-client carries desktop natives for the JVM; Android natives come
        // from libsignal-android, whose testing variant is not needed in the APK.
        resources.excludes += listOf(
            "libsignal_jni*.so",
            "libsignal_jni*.dylib",
            "signal_jni*.dll",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:net"))
    implementation(project(":core:signal"))
    implementation(libs.libsignal.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.unifiedpush.connector)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
