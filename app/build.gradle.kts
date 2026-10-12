plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries.android)
}

import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

// SHA-256 of the packaged geocoder index, computed at build time so the app can
// detect a stale/foreign on-device cache by hashing only the cached file (the
// packaged asset is immutable and its hash is known — no per-load asset hash).
// Empty when the asset is absent (fresh clone); the app then reports the usual
// "asset missing" error at load time.
// Whether the APK carries the Queensland routing graph and geocoder index.
// Debug builds bundle them by default so every documented test command keeps
// working; release builds do not (the map arrives as one `.motomap` package).
// `-Pmoto.bundleData=true|false` forces either for every variant — CI builds
// the distributed APK with `false`.
val defaultMapServer: String = (findProperty("moto.mapServer") as String? ?: "https://maps.curvemaps.app").trim().also {
    require(it.isEmpty() || it.startsWith("https://")) { "moto.mapServer must be an https URL (or empty)" }
    require(!it.contains('"') && !it.contains('\\')) { "moto.mapServer contains invalid characters" }
}

val bundleDataOverride: Boolean? = (findProperty("moto.bundleData") as String?)?.lowercase()?.let {
    when (it) {
        "true" -> true
        "false" -> false
        else -> throw GradleException("moto.bundleData must be 'true' or 'false', not '$it'.")
    }
}
fun bundlesDataFor(variant: String): Boolean = bundleDataOverride ?: (variant == "debug")

val geocoderSha256: String = run {
    val asset = file("src/main/assets/geocoder/geocoder.dat")
    if (!asset.isFile || asset.length() == 0L) {
        ""
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        asset.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

android {
    namespace = "com.curvemaps.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.curvemaps.app"
        minSdk = 26
        targetSdk = 35
        // Packaging-time overrides for the local F-Droid test repo
        // (tools/fdroid-repo/publish.sh); defaults keep normal builds intact.
        versionCode = findProperty("moto.versionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = findProperty("moto.versionName")?.toString() ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MapLibre ships its native renderer for four ABIs (11 MB each). 32-bit
        // x86 Android hardware is extinct; x86_64 stays for emulators and
        // Chromebooks, and Play's per-ABI splits deliver one ABI anyway.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        buildConfigField("String", "GEOCODER_SHA256", "\"$geocoderSha256\"")
        // The hosted map service the Maps screen offers by default
        // ("Browse maps"). Override with -Pmoto.mapServer=<https URL>, or
        // -Pmoto.mapServer= (empty) for a build with no default server.
        buildConfigField("String", "DEFAULT_MAP_SERVER", "\"$defaultMapServer\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "BUNDLED_DATA", bundlesDataFor("debug").toString())
        }
        release {
            buildConfigField("boolean", "BUNDLED_DATA", bundlesDataFor("release").toString())
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The geocoder index is generated from the selected OSM extract and is
// intentionally gitignored. Do not allow an APK to be built successfully while
// silently omitting the offline place-search feature.
tasks.register("validateGeocoderAsset") {
    val index = file("src/main/assets/geocoder/geocoder.dat")
    doLast {
        if (!index.isFile || index.length() == 0L) {
            throw GradleException(
                "Missing app/src/main/assets/geocoder/geocoder.dat. " +
                    "Build it with :geocoder-tool before assembling the APK."
            )
        }
    }
}

// The geocoder asset is only required (and only packaged) in variants that
// bundle Queensland data; the others take their map from a `.motomap` package.
tasks.named("preBuild") {
    if (bundlesDataFor("debug") || bundlesDataFor("release")) {
        dependsOn("validateGeocoderAsset")
    }
}

// LGPL guard. The app never reads an OSM PBF on the device (graphs are
// imported on the desktop with tools/gh/MotoGraphImport.java and shipped or
// installed as files), so the LGPL-3.0 Osmosis OSM-binary jar that GraphHopper
// declares for its PBF reader is excluded in the dependencies block and must
// stay out of every APK. This fails any APK build (preBuild) whose debug or release runtime
// classpath resolves an Osmosis / OSM-binary module through any path.
// OsmosisExcludedFromAppTest additionally scans the unit-test classpath for
// the classes themselves. Re-adding one needs a licensing decision first
// (THIRD_PARTY.md), not a change here.
val checkNoOsmosisInApp = tasks.register("checkNoOsmosisInApp") {
    group = "verification"
    description = "Fails if an LGPL Osmosis/OSM-binary module is on an APK runtime classpath."
    val forbiddenGroups = setOf("org.openstreetmap.osmosis", "org.openstreetmap.pbf")
    val roots = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").associateWith { name ->
        configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
    }
    doLast {
        val violations = roots.flatMap { (configuration, root) ->
            val seen = mutableSetOf<ComponentIdentifier>()
            val queue = ArrayDeque(listOf(root.get()))
            val found = mutableListOf<String>()
            while (queue.isNotEmpty()) {
                val component = queue.removeFirst()
                if (!seen.add(component.id)) continue
                val module = component.moduleVersion
                if (module != null && module.group in forbiddenGroups) {
                    found += "$configuration: ${module.group}:${module.name}:${module.version}"
                }
                component.dependencies.filterIsInstance<ResolvedDependencyResult>()
                    .forEach { queue.addLast(it.selected) }
            }
            found
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "LGPL Osmosis/OSM-binary code is on the app's runtime classpath:\n  " +
                    violations.joinToString("\n  ") +
                    "\nThe app never reads PBF files; exclude it (see app/build.gradle.kts and THIRD_PARTY.md).",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNoOsmosisInApp) }

// Variants that do not bundle data must not ship the generated graph and
// geocoder: the assets live in src/main/assets (the pipeline's fixed output
// location), so strip them from the merged assets before packaging.
val unbundledAssets = listOf("graph-cache", "geocoder")
androidComponents.onVariants { variant ->
    if (!bundlesDataFor(variant.name)) {
        val taskName = "merge${variant.name.replaceFirstChar { it.uppercase() }}Assets"
        tasks.matching { it.name == taskName }.configureEach {
            doLast {
                outputs.files.files.forEach { dir ->
                    unbundledAssets.forEach { name -> File(dir, name).deleteRecursively() }
                }
            }
        }
    }
}

// MapLibre 13.5.0 publishes one rendering backend per artifact and the
// published default (`org.maplibre.gl:android-sdk`) is Vulkan-only: its
// RenderingEngine returns VULKAN and rejects any runtime switch. The Android
// emulator rasterizes Vulkan through gfxstream/llvmpipe in software (measured:
// `cmd gpu vkjson` reports llvmpipe for the dense Brisbane CBD style) while its
// OpenGL ES path is translated onto the host GPU (measured: "Android Emulator
// OpenGL ES Translator (Apple M4 Pro)"), so debug builds default to the
// genuine OpenGL artifact and release keeps the published Vulkan default.
// `-PmaplibreBackend=vulkan|opengl` forces one backend for both variants, which
// is how the A/B benchmark in tools/test/visual.py builds its baseline.
val maplibreBackendOverride: String? = (findProperty("maplibreBackend") as String?)?.lowercase()
fun maplibreBackendFor(variant: String): String = when (maplibreBackendOverride) {
    null -> if (variant == "debug") "opengl" else "vulkan"
    "opengl", "vulkan" -> maplibreBackendOverride
    else -> throw GradleException(
        "Unknown maplibreBackend '$maplibreBackendOverride'; use 'vulkan' or 'opengl'.",
    )
}

dependencies {
    add(
        "debugImplementation",
        if (maplibreBackendFor("debug") == "opengl") libs.maplibre.android.sdk.opengl else libs.maplibre.android.sdk,
    )
    add(
        "releaseImplementation",
        if (maplibreBackendFor("release") == "opengl") libs.maplibre.android.sdk.opengl else libs.maplibre.android.sdk,
    )
    implementation(libs.graphhopper.core) {
        // The Osmosis OSM-binary (PBF) jar is LGPL-3.0. GraphHopper needs it
        // (and protobuf-java, which only it needs) solely for OSMReader's PBF
        // import, which never runs on the phone: graphs are imported on the
        // desktop and the app only loads them. See checkNoOsmosisInApp above.
        exclude(group = "org.openstreetmap.osmosis", module = "osmosis-osm-binary")
    }
    implementation(libs.slf4j.android)

    implementation(platform(libs.compose.bom))
    testImplementation(libs.kxml)
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Saved-route storage tests: pure JVM suites for the codec/projection/
    // similarity/repository logic, instrumented suites for the real SQLite.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Espresso 3.5 reflectively calls InputManager.getInstance(), removed on API 35.
    // Pin the API-35-compatible release for Compose and AndroidX test rules.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.rules)
    testImplementation(libs.org.json)
}

// --- Open-source licences screen data -----------------------------------------
//
// The AboutLibraries Gradle plugin (build-time only, Apache-2.0; no runtime
// library) walks each variant's resolved dependency graph and writes
// res/raw/aboutlibraries.json, so the in-app list follows the dependencies
// automatically. It runs offline: every licence text and the data/font/style
// entries (OpenStreetMap, OpenMapTiles, OSM Bright, Noto) are checked in under
// app/licences/. collect<Variant>LicenceNotices below adds the NOTICE/LICENSE
// files the shipped jars carry plus the checked-in upstream copyright notices
// (app/licences/notices/). ReleaseLicenceCoverageTest compares the release
// output with the release runtime classpath.
aboutLibraries {
    offlineMode = true
    collect {
        configPath = file("licences")
        // BOMs only pin versions; nothing of theirs ships in the APK.
        includePlatform = false
    }
    export {
        excludeFields.addAll("funding", "scm")
    }
}

/**
 * Collects the licence notices of one variant's runtime classpath into a
 * generated asset (`licences/notices.json`): every NOTICE/LICENSE/COPYING file
 * inside a shipped jar, plus the checked-in upstream copyright notices for
 * components whose artifacts carry none. Also writes the variant's shipped
 * module list for ReleaseLicenceCoverageTest.
 */
abstract class CollectLicenceNotices : DefaultTask() {
    @get:Internal
    lateinit var runtimeArtifacts: ArtifactCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val runtimeArtifactFiles: FileCollection
        get() = runtimeArtifacts.artifactFiles

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val checkedInNotices: DirectoryProperty

    @get:OutputDirectory
    abstract val assetsDir: DirectoryProperty

    /** The resolved graph, to name Kotlin Multiplatform artifacts by their root module. */
    @get:Internal
    abstract val resolvedRoot: Property<ResolvedComponentResult>

    @get:OutputFile
    abstract val modulesFile: RegularFileProperty

    @TaskAction
    fun collect() {
        // `androidx.compose.ui:ui-android` ships under the published root
        // `androidx.compose.ui:ui` (a Gradle `available-at` redirect); the
        // licence list names the root, so the module list does too.
        val platformToRoot = mutableMapOf<String, String>()
        val seen = mutableSetOf<ComponentIdentifier>()
        val pending = ArrayDeque<ResolvedComponentResult>().apply { add(resolvedRoot.get()) }
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!seen.add(component.id)) continue
            val rootId = component.id as? ModuleComponentIdentifier
            if (rootId != null) {
                component.variants.forEach { variant ->
                    val platform = variant.externalVariant.orElse(null)?.owner as? ModuleComponentIdentifier
                    if (platform != null) {
                        platformToRoot["${platform.group}:${platform.module}"] = "${rootId.group}:${rootId.module}"
                    }
                }
            }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.add(it.selected) }
        }
        val noticeName = Regex("(?i)(^|[-_.])(notice|license|licence|copying)([-_.][^/]*)?$")
        val notices = sortedMapOf<String, MutableList<Pair<String, String>>>()
        val modules = sortedSetOf<String>()
        runtimeArtifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@forEach
            val artifactModule = "${id.group}:${id.module}"
            val module = platformToRoot[artifactModule] ?: artifactModule
            modules += module
            val file = artifact.file
            if (!file.isFile || !(file.name.endsWith(".jar") || file.name.endsWith(".aar"))) return@forEach
            val zip = ZipFile(file)
            try {
                val entries: List<ZipEntry> = Collections.list(zip.entries())
                    .filter { entry: ZipEntry ->
                        !entry.isDirectory && !entry.name.endsWith(".class") &&
                            noticeName.containsMatchIn(entry.name.substringAfterLast('/'))
                    }
                    .sortedBy { entry: ZipEntry -> entry.name }
                for (entry in entries) {
                    val stream = zip.getInputStream(entry)
                    val text = try {
                        String(stream.readBytes(), Charsets.UTF_8).trim()
                    } finally {
                        stream.close()
                    }
                    // A jar's full Apache-2.0 LICENSE repeats the licence text
                    // the screen already shows; its NOTICE files are kept.
                    val apacheLicence = text.contains("Apache License") &&
                        text.contains("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION") &&
                        !entry.name.contains("NOTICE", ignoreCase = true)
                    if (text.isNotEmpty() && !apacheLicence) {
                        notices.getOrPut(module) { mutableListOf() }.add(entry.name to text)
                    }
                }
            } finally {
                zip.close()
            }
        }
        checkedInNotices.get().asFile.listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                @Suppress("UNCHECKED_CAST")
                val doc = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
                val name = doc["name"] as String
                val text = (doc["text"] as String).trim()
                @Suppress("UNCHECKED_CAST")
                (doc["modules"] as List<String>).forEach { module ->
                    notices.getOrPut(module) { mutableListOf() } += name to text
                }
            }
        val json = groovy.json.JsonOutput.toJson(
            notices.mapValues { (_, files) -> files.map { (name, text) -> mapOf("name" to name, "text" to text) } },
        )
        val out = assetsDir.get().asFile.resolve("licences")
        out.deleteRecursively()
        out.mkdirs()
        out.resolve("notices.json").writeText(json)
        modulesFile.get().asFile.apply { parentFile.mkdirs() }.writeText(modules.joinToString("\n", postfix = "\n"))
    }
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val collect = tasks.register<CollectLicenceNotices>("collect${capitalized}LicenceNotices") {
        runtimeArtifacts = variant.runtimeConfiguration.incoming.artifactView {
            attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
        }.artifacts
        resolvedRoot.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
        checkedInNotices.set(layout.projectDirectory.dir("licences/notices"))
        modulesFile.set(layout.buildDirectory.file("licences/${variant.name}/runtime-modules.txt"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(collect, CollectLicenceNotices::assetsDir)
}

// The JVM suites read the RELEASE licence data, whatever variant they run on,
// so a dependency that ships without a listed licence fails ./gradlew test.
tasks.withType<Test>().configureEach {
    dependsOn("prepareLibraryDefinitionsRelease", "collectReleaseLicenceNotices")
    // The route rater (tools/rater/run.sh) serves whole-state graphs and parallel plans from this JVM;
    // Gradle's default 512 MB runs out on New South Wales.
    if (providers.environmentVariable("RATER").orNull == "1") maxHeapSize = "4g"
    val generated = layout.buildDirectory
    systemProperty(
        "curvemaps.licences.releaseDefinitions",
        generated.file("generated/aboutLibraries/release/res/raw/aboutlibraries.json").get().asFile.absolutePath,
    )
    systemProperty(
        "curvemaps.licences.releaseNotices",
        generated.dir("generated/assets/collectReleaseLicenceNotices").get().asFile
            .resolve("licences/notices.json").absolutePath,
    )
    systemProperty(
        "curvemaps.licences.releaseModules",
        generated.file("licences/release/runtime-modules.txt").get().asFile.absolutePath,
    )
}
