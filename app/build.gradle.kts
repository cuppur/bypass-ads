import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.reflect.full.declaredMemberProperties

fun String.runCommand(): String {
    val process = ProcessBuilder(split(" "))
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    if (exitCode != 0) {
        error("Command failed with exit code $exitCode: $output")
    }
    return output
}

data class GitInfo(
    val commitId: String,
    val commitTime: String,
    val tagName: String?,
) {
    val versionNameSuffix get() = if (tagName == null) ("-" + commitId.take(7)) else null
}

val gitInfo = GitInfo(
    commitId = "git rev-parse HEAD".runCommand(),
    commitTime = "git log -1 --format=%ct".runCommand() + "000",
    tagName = runCatching { "git describe --tags --exact-match".runCommand() }.getOrNull(),
)

val debugSuffixPairList by lazy {
    javax.xml.parsers.DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(file("$projectDir/src/main/res/values/strings.xml"))
        .documentElement.getElementsByTagName("string").run {
            (0 until length).mapNotNull { i ->
                val node = item(i)
                if (node.attributes.getNamedItem("debug_suffix") != null) {
                    val key = node.attributes.getNamedItem("name").nodeValue
                    val value = node.textContent
                    key to value
                } else {
                    null
                }
            }
        }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlinx.atomicfu)
    alias(libs.plugins.google.ksp)
    alias(libs.plugins.remap)
    alias(libs.plugins.loc)
}

android {
    namespace = rootProject.ext["android.namespace"].toString()
    compileSdk = rootProject.ext["android.compileSdk"] as Int
    buildToolsVersion = rootProject.ext["android.buildToolsVersion"].toString()

    defaultConfig {
        minSdk = rootProject.ext["android.minSdk"] as Int
        targetSdk = rootProject.ext["android.targetSdk"] as Int

        applicationId = "app.bypassads"
        // Bypass Ads v1.0.0 self-use version. The bundled GKD engine stays at
        // 1.12.1 (kept as an internal constant, not advertised in the product
        // UI). versionName is the exact release string "1.0.0" — the commit
        // SHA suffix is only applied to debug builds (see GitInfo below).
        versionCode = 100
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        androidResources {
            localeFilters += listOf("zh", "en")
        }
        ndk {
            // noinspection ChromeOsAbiSupport
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        GitInfo::class.declaredMemberProperties.onEach {
            manifestPlaceholders[it.name] = it.get(gitInfo) ?: ""
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        resValues = true
    }

    // v1.0 signing policy:
    //  - GKD release NEVER falls back to the debug key. Without a formal
    //    signing config (GKD_STORE_FILE / GKD_STORE_PASSWORD / GKD_KEY_ALIAS /
    //    GKD_KEY_PASSWORD via -P or gradle.properties) the release buildType
    //    is UNSIGNED: assembleGkdRelease still builds (CI / R8 validation),
    //    and tools/build_selfuse.ps1 refuses to ship an unsigned "release".
    //  - Debug keeps the debug key so on-device debugging keeps working.
    val gkdReleaseSigning = if (project.hasProperty("GKD_STORE_FILE")) {
        signingConfigs.create("gkd") {
            storeFile = file(project.properties["GKD_STORE_FILE"] as String)
            storePassword = project.findProperty("GKD_STORE_PASSWORD")?.toString()
            keyAlias = project.findProperty("GKD_KEY_ALIAS")?.toString()
            keyPassword = project.findProperty("GKD_KEY_PASSWORD")?.toString()
        }
    } else {
        null
    }

    val playSigningConfig = if (project.hasProperty("PLAY_STORE_FILE")) {
        signingConfigs.create("play") {
            storeFile = file(project.properties["PLAY_STORE_FILE"].toString())
            storePassword = project.properties["PLAY_STORE_PASSWORD"].toString()
            keyAlias = project.properties["PLAY_KEY_ALIAS"].toString()
            keyPassword = project.properties["PLAY_KEY_PASSWORD"].toString()
        }
    } else {
        gkdReleaseSigning
    }

    buildTypes {
        debug {
            // Debug carries the commit SHA suffix (helps identify on-device
            // builds); RELEASE must be exactly "1.0.0" — no suffix.
            versionNameSuffix = gitInfo.versionNameSuffix
            // Debug builds always use the debug key, regardless of any
            // GKD_STORE_* configuration (release signing is for release only).
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            resValue("color", "better_black", "#FF5D92")
            debugSuffixPairList.onEach { (key, value) ->
                resValue("string", key, "$value-debug")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            // null when no formal key is configured -> UNSIGNED release
            // (never the debug key).
            signingConfig = gkdReleaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    productFlavors {
        flavorDimensions += "channel"
        create("gkd") {
            isDefault = true
            signingConfig = gkdReleaseSigning
            resValue("bool", "is_accessibility_tool", "true")
        }
        create("play") {
            signingConfig = playSigningConfig
            resValue("bool", "is_accessibility_tool", "false")
            manifestPlaceholders["channel"] = "bypassads"
        }
        create("fulltools") {
            signingConfig = gkdReleaseSigning
            applicationIdSuffix = ".fulltools"
            resValue("bool", "is_accessibility_tool", "true")
            manifestPlaceholders["channel"] = "fulltools"
        }
        all {
            dimension = flavorDimensions.first()
            // Bypass Ads: keep flavor name "gkd" for build/task compatibility,
            // but brand the channel as bypassads so GKD channel-gated surfaces
            // (donation, update channel picker) stay hidden.
            manifestPlaceholders.putIfAbsent("channel", "bypassads")
        }
    }
    compileOptions {
        sourceCompatibility = rootProject.ext["android.javaVersion"] as JavaVersion
        targetCompatibility = rootProject.ext["android.javaVersion"] as JavaVersion
    }
    dependenciesInfo.includeInApk = false
    packaging.resources.excludes += setOf(
        // https://github.com/Kotlin/kotlinx.coroutines/issues/2023
        "META-INF/**", "**/attach_hotspot_windows.dll",

        "**.properties", "**.bin", "**/*.proto",
        "**/kotlin-tooling-metadata.json",

        // ktor
        "**/custom.config.conf",
        "**/custom.config.yaml",
    )
}

if (project.hasProperty("GKD_RENAME_APK_FLAG")) {
    androidComponents.onVariants { variant ->
        variant.outputs.onEach { output ->
            output as VariantOutputImpl
            output.outputFileName = "bypass-ads-v${output.versionName.get()}.apk"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(rootProject.ext["kotlin.jvmTarget"] as JvmTarget)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-Xcontext-parameters",
            "-Xexplicit-backing-fields",
            "-XXLanguage:+MultiDollarInterpolation",
        )
    }
}

// https://developer.android.com/jetpack/androidx/releases/room?hl=zh-cn#compiler-options
room {
    schemaDirectory("$projectDir/schemas")
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFiles.addAll(
        rootProject.layout.projectDirectory.file("stability_config.conf"),
    )
}

loc {
    template = "{packageName}.{methodName}({fileName}:{lineNumber})"
}

dependencies {
    // Bundled Chinese model: local recognition, no GMS model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation(libs.kotlin.stdlib)

    implementation(project(":selector"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.compose.get()}")
    androidTestImplementation(libs.compose.junit4)

    implementation(libs.compose.activity)
    implementation(libs.compose.material3)

    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)

    compileOnly(project(":hidden_api"))
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
    implementation(libs.lsposed.hiddenapibypass)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.google.accompanist.drawablepainter)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)
    // https://github.com/Kotlin/kotlinx-atomicfu/issues/145
    implementation(libs.kotlinx.atomicfu)

    implementation(libs.activityResultLauncher)

    implementation(libs.reorderable)

    implementation(libs.androidx.splashscreen)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable)

    implementation(libs.exp4j)

    implementation(libs.toaster)
    implementation(libs.permissions)
    implementation(libs.device)

    implementation(libs.json5)
    compileOnly(libs.loc.annotation)

    implementation(libs.kevinnzouWebview)
}
