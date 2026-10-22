plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kanagawa.yamada.inaho"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kanagawa.yamada.inaho"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add("src/main/jniLibs")
        }
    }
}

tasks.register<Exec>("buildRustNative") {
    val rustDir = file("${project.rootDir}/../inaho_rust")
    val cargoPath = File("${System.getProperty("user.home")}/.cargo/bin/cargo")
    val cargoBin = if (cargoPath.exists()) cargoPath.absolutePath else "cargo"

    onlyIf { rustDir.exists() }
    workingDir = rustDir
    environment("PATH", "${System.getProperty("user.home")}/.cargo/bin:" + System.getenv("PATH"))
    commandLine(cargoBin, "ndk", "-t", "arm64-v8a", "-o", "${projectDir}/src/main/jniLibs", "build", "--release")
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn("buildRustNative")
}

dependencies {
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media:media:1.7.0")
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation("sh.calvin.reorderable:reorderable:2.4.0")
    implementation("net.jthink:jaudiotagger:3.0.1")
}