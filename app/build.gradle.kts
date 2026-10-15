import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val prepareProposalSchema = tasks.register<Sync>("prepareProposalSchema") {
    from(rootProject.file("docs/CONTRATOS/IA/v1/proposals.schema.json"))
    into(layout.buildDirectory.dir("generated/proposalSchema"))
}

// Synthetic review examples are available only in debug. Keep the canonical fixtures under docs/.
val prepareProposalReviewDemo = tasks.register<Sync>("prepareProposalReviewDemo") {
    from(rootProject.file("docs/CONTRATOS/IA/v1/fixtures/sources.json")) {
        into("proposal-review")
    }
    from(rootProject.file("docs/CONTRATOS/IA/v1/fixtures/valid")) {
        into("proposal-review/fixtures")
        include("*.json")
    }
    into(layout.buildDirectory.dir("generated/proposalReviewDemo"))
}

android {
    namespace = "com.yezvi.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yezvi.assistant"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Opt-in target with a separate Android UID; never shares the personal app database.
            if (providers.gradleProperty("isolatedPersistenceTests").orNull == "true") {
                applicationIdSuffix = ".persistenceisolated"
            }
        }
        release {
            proguardFiles("proguard-rules.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // Package the single canonical exchange schema, never the synthetic fixtures or sources.
    sourceSets.getByName("main").resources.directories.add(layout.buildDirectory.dir("generated/proposalSchema").get().asFile.path)
    // The F4.07 review route is an explicitly labeled debug demonstration, never release data.
    sourceSets.getByName("debug").assets.srcDir(
        layout.buildDirectory.dir("generated/proposalReviewDemo").get().asFile.path,
    )
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn(prepareProposalReviewDemo)
}

dependencies {
    // Own pinned build: Sherpa JNI without TTS/diarization + one shared ORT per ABI.
    releaseImplementation(files("libs/sherpa-onnx-asr-only-1.13.8-yezvi.aar"))
    debugImplementation(files("libs/sherpa-onnx-asr-only-confidence-1.13.8-yezvi.aar"))
    // Experimental comparison only; Vosk did not pass the speed/quality acceptance gate.
    debugImplementation(files("libs/vosk-android-0.3.75.aar", "libs/jna-5.18.1.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.androidx.sqlite.async)
    ksp(libs.androidx.room3.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Local preparation is explicit, never a network/download operation during a build.
val verifyLocalSpeechResources = tasks.register("verifyLocalSpeechResources") {
    val resourceManifest = layout.projectDirectory.file("src/main/assets/stt/resources-manifest.json")
    val workspaceDirectory = rootProject.layout.projectDirectory.asFile
    val requiredFiles = listOf(
        "libs/sherpa-onnx-asr-only-1.13.8-yezvi.aar",
        "src/main/assets/stt/nemo-es-rnnt-int8/encoder.int8.onnx",
        "src/main/assets/stt/nemo-es-rnnt-int8/decoder.int8.onnx",
        "src/main/assets/stt/nemo-es-rnnt-int8/joiner.int8.onnx",
        "src/main/assets/stt/nemo-es-rnnt-int8/tokens.txt",
        "src/main/assets/stt/nemo-es-rnnt-int8/bpe.vocab",
        "src/main/assets/stt/silero_vad.onnx",
    ).map { layout.projectDirectory.file(it) }
    inputs.files(requiredFiles)
    inputs.file(resourceManifest)
    doLast {
        check(requiredFiles.all { it.asFile.isFile }) {
            "F3.06 resources missing. Run scripts/Prepare-LocalSpeechResources.ps1 explicitly; " +
                "see docs/IMPLEMENTACIONES/F3.06-etapa1-runtime.md. No build download is allowed."
        }
        val manifest = groovy.json.JsonSlurper().parse(resourceManifest.asFile) as Map<*, *>
        val resources = manifest["resources"] as List<*>
        check(resources.size == requiredFiles.size) { "Unexpected F3.06 resource inventory" }
        for (value in resources) {
            val resource = value as Map<*, *>
            val artifact = File(workspaceDirectory, resource["path"] as String).canonicalFile
            check(requiredFiles.any { it.asFile.canonicalFile == artifact }) { "Unknown F3.06 artifact" }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(artifact.length() == (resource["bytes"] as Number).toLong() && sha256 == resource["sha256"]) {
                "F3.06 resource integrity failure: ${artifact.name}. Review the pinned inventory; no automatic replacement."
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyLocalSpeechResources, prepareProposalSchema) }

val verifyConfidenceRuntime = tasks.register("verifyConfidenceRuntime") {
    val manifestFile = layout.projectDirectory.file("src/debug/assets/stt/confidence-runtime.json")
    val artifact = layout.projectDirectory.file("libs/sherpa-onnx-asr-only-confidence-1.13.8-yezvi.aar")
    inputs.files(manifestFile, artifact)
    doLast {
        check(manifestFile.asFile.isFile && artifact.asFile.isFile) {
            "Missing experimental confidence runtime. Build explicitly with scripts/Build-SherpaAsrOnly.ps1 -ConfidencePrototype; no build download."
        }
        val spec = groovy.json.JsonSlurper().parse(manifestFile.asFile) as Map<*, *>
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.asFile.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(sha == spec["sha256"] && artifact.asFile.length() == (spec["bytes"] as Number).toLong()) {
            "Experimental confidence AAR differs from its reviewed inventory"
        }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(verifyConfidenceRuntime) }

val verifyVoskResources = tasks.register("verifyVoskResources") {
    val manifestFile = layout.projectDirectory.file("src/debug/assets/stt/vosk-resources.json")
    val workspaceDirectory = rootProject.layout.projectDirectory.asFile
    val allowedDirectory = layout.projectDirectory.asFile.canonicalFile.toPath()
    inputs.file(manifestFile)
    // Validate every time, including model files restored outside Gradle.
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(manifestFile.asFile) as Map<*, *>
        val resources = manifest["resources"] as List<*>
        check(resources.size == 16) { "Unexpected Vosk resource inventory" }
        for (value in resources) {
            val spec = value as Map<*, *>
            val file = File(workspaceDirectory, spec["path"] as String).canonicalFile
            check(file.toPath().startsWith(allowedDirectory) && file.isFile) {
                "Missing Vosk resource. Run scripts/Prepare-VoskResources.ps1; build never downloads models."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(file.length() == (spec["bytes"] as Number).toLong() && hash == spec["sha256"]) {
                "Pinned Vosk resource integrity failure: ${file.name}"
            }
        }
    }
}
tasks.matching { it.name == "preDebugBuild" }.configureEach { dependsOn(verifyVoskResources) }

val verifyReleaseSpeechIsolation = tasks.register("verifyReleaseSpeechIsolation") {
    dependsOn("packageRelease")
    val apkDirectory = layout.buildDirectory.dir("outputs/apk/release")
    val noticeManifestFile = layout.projectDirectory.file("src/main/assets/stt/licenses-manifest.json").asFile
    val baselineRuntimeFile = layout.projectDirectory.file("libs/sherpa-onnx-asr-only-1.13.8-yezvi.aar").asFile
    inputs.file(noticeManifestFile)
    inputs.file(baselineRuntimeFile)
    doLast {
        val apks = apkDirectory.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty()
        check(apks.isNotEmpty()) { "No release APK to audit" }
        for (apk in apks) ZipFile(apk).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check(names.none { name ->
                name.contains("vosk", ignoreCase = true) || name.contains("jnidispatch", ignoreCase = true) ||
                    name.contains("confidence", ignoreCase = true) ||
                    name.contains("espeak", ignoreCase = true) ||
                    name.contains("piper-phonemize", ignoreCase = true) || name.contains("ucd-tools", ignoreCase = true) ||
                    name.startsWith("assets/stt-stage2/") || name.contains("comparison", ignoreCase = true)
            }) { "Release contains an experimental runtime or research fixture" }
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                check(names.contains("lib/$abi/libsherpa-onnx-jni.so") && names.contains("lib/$abi/libonnxruntime.so")) {
                    "Release ASR-only JNI/ORT runtime missing for $abi"
                }
                ZipFile(baselineRuntimeFile).use { baseline ->
                    fun entryHash(archive: ZipFile, path: String): String {
                        val digest = MessageDigest.getInstance("SHA-256")
                        archive.getInputStream(checkNotNull(archive.getEntry(path))).use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    }
                    for (library in listOf("libsherpa-onnx-jni.so", "libonnxruntime.so")) {
                        check(entryHash(zip, "lib/$abi/$library") == entryHash(baseline, "jni/$abi/$library")) {
                            "Release must retain the reviewed baseline runtime: $abi/$library"
                        }
                    }
                }
            }
            val manifest = groovy.json.JsonSlurper().parse(noticeManifestFile) as Map<*, *>
            for (notice in manifest["notices"] as List<*>) {
                val spec = notice as Map<*, *>
                val path = "assets/stt/licenses/${spec["file"]}"
                val entry = checkNotNull(zip.getEntry(path)) { "Release notice missing: $path" }
                val hash = MessageDigest.getInstance("SHA-256").digest(zip.getInputStream(entry).use { it.readBytes() })
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(hash == spec["sha256"]) { "Release notice changed: $path" }
            }
            check(names.contains("assets/stt/ATTRIBUTIONS.md")) { "Release ASR attribution index missing" }
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyReleaseSpeechIsolation) }
