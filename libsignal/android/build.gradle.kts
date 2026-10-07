import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// libsignal built from source (../scripts/build-libsignal.sh), replacing the Maven artifacts
// org.signal:libsignal-client and org.signal:libsignal-android in the app. Only included with
// -PlibsignalBuiltFromSource. The script checks out the pinned libsignal commit into
// ../build/source/libsignal (Java/Kotlin API compiled here, unchanged) and writes the
// stripped JNI library and notices into ../build/output. Nothing from libsignal is committed.
val libsignalDir = projectDir.parentFile
val checkout = libsignalDir.resolve("build/source/libsignal")
val output = libsignalDir.resolve("build/output")
val lockFile = libsignalDir.resolve("source.lock.json")
// Local experiments on other hosts/toolchains may produce different native bytes.
val allowUnpinned = providers.gradleProperty("allowUnpinnedLibsignal").isPresent

@Suppress("UNCHECKED_CAST")
val lock = JsonSlurper().parse(lockFile) as Map<String, Map<String, Any>>
val lockSource = lock.getValue("source")
val lockBuild = lock.getValue("build")
check(lockSource["tag"] == "v${libs.versions.libsignal.get()}") {
    "libsignal/source.lock.json source.tag ${lockSource["tag"]} does not match the libsignal " +
        "version ${libs.versions.libsignal.get()} in gradle/libs.versions.toml."
}

android {
    namespace = "org.signal.libsignal"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        val sources = variant.sources
        listOf("java/client/src/main/java", "java/shared/java", "java/android/src/main/java").forEach { dir ->
            checkNotNull(sources.java).addStaticSourceDirectory(checkout.resolve(dir).path)
            checkNotNull(sources.kotlin).addStaticSourceDirectory(checkout.resolve(dir).path)
        }
        checkNotNull(sources.resources).addStaticSourceDirectory(checkout.resolve("java/shared/resources").path)
        checkNotNull(sources.assets).addStaticSourceDirectory(output.resolve("assets").path)
        checkNotNull(sources.jniLibs).addStaticSourceDirectory(output.resolve("jniLibs").path)
    }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

val verifyLibsignal = tasks.register("verifyLibsignal") {
    group = "verification"
    description = "Verify the source-built libsignal against libsignal/source.lock.json."
    val provenance = output.resolve("PROVENANCE")
    val checksums = output.resolve("SHA256SUMS")
    inputs.file(lockFile)
    inputs.files(provenance, checksums).optional()
    doLast {
        check(provenance.isFile && checksums.isFile && checkout.resolve(".git").isDirectory) {
            "libsignal has not been built from source; run libsignal/scripts/build-libsignal.sh " +
                "(see libsignal/README.md)."
        }
        val built = provenance.readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val expected = mapOf(
            "source" to lockSource["repository"],
            "commit" to lockSource["commit"],
            "ndk" to lockBuild["ndkRevision"],
        )
        check(expected.all { (key, value) -> built[key] == value }) {
            "libsignal build/output/PROVENANCE $built does not match source.lock.json $expected."
        }
        val actual = checksums.readLines().filter { it.isNotBlank() }
            .associate { it.substringAfter("  ").removePrefix("*") to it.substringBefore("  ") }
        check(actual.keys == setOf("build/output/jniLibs/arm64-v8a/libsignal_jni.so")) {
            "libsignal SHA256SUMS lists ${actual.keys}, expected only the arm64-v8a libsignal_jni.so."
        }
        actual.forEach { (path, hash) ->
            check(sha256(libsignalDir.resolve(path)) == hash) {
                "libsignal/$path changed after the source build (SHA256SUMS)."
            }
        }
        @Suppress("UNCHECKED_CAST")
        val pinned = lockBuild["expectedSha256"] as Map<String, String>?
        if (!allowUnpinned) {
            check(actual == pinned) {
                "The source-built libsignal is not the reproducible one pinned in " +
                    "libsignal/source.lock.json (build.expectedSha256 $pinned, built $actual). Build it " +
                    "in the F-Droid buildserver image (scripts/fdroid-rb-docker.sh), or pass " +
                    "-PallowUnpinnedLibsignal for a local experiment."
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyLibsignal) }
