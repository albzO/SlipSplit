plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.ocrbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.ocrbridge"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.2"
        testInstrumentationRunner = providers.gradleProperty("smokeRunner")
            .getOrElse("local.ocrbridge.BoundarySmoke")
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
    }

    buildTypes {
        release {
            // R8 drops unused Compose and Kotlin code and the resources nothing refers to.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release key exists, so it installs over debug builds' data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The app loads the ORT-format copies; the .onnx files next to them are only the conversion source.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!*.onnx"
    }

    // Deploy only the device's native libraries instead of shipping emulator libraries too.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // ONNX Runtime 1.31.1 built with only the operators in ../ort/ops.config; see ../ort/README.md.
    implementation(files("libs/onnxruntime-android-minimal-1.31.1.aar"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// The minimal ONNX Runtime reads only ORT-format models, so stop early if a model was not converted.
val checkOrtModels by tasks.registering {
    val assets = layout.projectDirectory.dir("src/main/assets")
    inputs.files(fileTree(assets) { include("**/*.onnx", "**/*.ort") })
    doLast {
        val stale = fileTree(assets) { include("**/*.onnx") }.files.filter { onnx ->
            val ort = File(onnx.path.removeSuffix(".onnx") + ".ort")
            !ort.exists() || ort.lastModified() < onnx.lastModified()
        }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "Convert these models with SlipSplit/ort/convert_models.ps1: " +
                    stale.joinToString { it.relativeTo(assets.asFile).path }
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkOrtModels) }
