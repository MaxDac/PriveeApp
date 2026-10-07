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

// Set by the F-Droid recipe and the release workflow: use libsignal built from source
// (libsignal/README.md) instead of the Maven artifacts, which ship prebuilt native code.
val libsignalBuiltFromSource = providers.gradleProperty("libsignalBuiltFromSource").isPresent

// version.properties holds the version of the next release (scripts/release_version.py);
// the release workflow passes the tag's version explicitly.
val baseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val releaseVersionName = providers.gradleProperty("releaseVersionName").orNull
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orNull
require((releaseVersionName == null) == (releaseVersionCode == null)) {
    "Set both -PreleaseVersionName and -PreleaseVersionCode, or neither."
}
require(!providers.gradleProperty("requireReleaseVersion").isPresent || releaseVersionName != null) {
    "Publishing requires explicit -PreleaseVersionName and -PreleaseVersionCode."
}
val resolvedVersionName = requireNotNull(releaseVersionName ?: baseVersion.getProperty("versionName")) {
    "version.properties must define versionName."
}
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?").matches(resolvedVersionName)) {
    "versionName must be X.Y.Z or X.Y.Z-prerelease, without leading zeros or build metadata."
}
require(resolvedVersionName.substringAfter('-', "").split('.').none {
    it.matches(Regex("[0-9]+")) && it.length > 1 && it.startsWith("0")
}) {
    "Numeric prerelease identifiers cannot have leading zeros."
}
val versionCodeText = releaseVersionCode ?: baseVersion.getProperty("versionCode")
val resolvedVersionCode = versionCodeText?.toIntOrNull()
require(versionCodeText != null && Regex("[1-9][0-9]*").matches(versionCodeText) &&
    resolvedVersionCode != null && resolvedVersionCode in 1..2_100_000_000) {
    "versionCode must be an integer between 1 and 2100000000."
}

android {
    namespace = "com.privee.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.privee.app"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
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
            // Published APKs ship arm64-v8a only, the one ABI libsignal is built from source for.
            ndk { abiFilters += "arm64-v8a" }
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
        // build-libsignal.sh strips the library with the pinned NDK; package those exact bytes.
        if (libsignalBuiltFromSource) jniLibs.keepDebugSymbols += "**/libsignal_jni.so"
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

if (libsignalBuiltFromSource) {
    // :libsignal:android holds both the Java API (also reached through :core:signal) and the
    // Android natives.
    configurations.configureEach {
        exclude(group = "org.signal", module = "libsignal-client")
        exclude(group = "org.signal", module = "libsignal-android")
    }
    dependencies { implementation(project(":libsignal:android")) }
}
