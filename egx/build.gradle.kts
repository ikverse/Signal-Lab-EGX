// Signal Lab EGX (docs/egx-plan.md): the second app, for the Egyptian Exchange. Its own name, id, icon, data and ledger;
// it shares only the engine with the crypto app, so the crypto app's build, tests and releases are untouched.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersionName = "0.1.1"
val appVersionCode = appVersionName.split(".").let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

/** Whatever `local.properties` holds. Git ignores that file: it is where this machine's secrets live. */
val localProperties: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val text = line.trim()
        if (text.startsWith("#") || "=" !in text) return@mapNotNull null
        text.substringBefore("=").trim() to text.substringAfter("=").trim()
    }
    ?.toMap()
    .orEmpty()

/** A setting from `local.properties` on a developer machine, or the environment on CI. */
fun buildSetting(name: String): String? =
    (localProperties[name] ?: System.getenv(name))?.takeIf(String::isNotBlank)

/**
 * The key release builds are signed with. Android refuses an update signed by a different key than
 * the install it would replace, so every release must carry the same signature, and the key has to
 * outlive this machine: it lives in GitHub secrets, never in the repository. Absent, the release
 * build is simply unsigned, so a fresh checkout still builds and CI still runs the tests.
 */
val releaseKeystore = buildSetting("SIGNAL_LAB_KEYSTORE_FILE")
    ?.let(rootProject::file)
    ?.takeIf { it.exists() }
val releaseKeystorePassword = buildSetting("SIGNAL_LAB_KEYSTORE_PASSWORD")
val releaseKeyAlias = buildSetting("SIGNAL_LAB_KEY_ALIAS")

android {
    namespace = "com.ikverse.signallab.egx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ikverse.signallab.egx"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystorePassword != null && releaseKeyAlias != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = buildSetting("SIGNAL_LAB_KEY_PASSWORD") ?: releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)

    testImplementation(testFixtures(project(":engine")))
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
