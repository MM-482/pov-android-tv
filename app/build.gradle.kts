plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// OneDrive can lock generated test directories. Script builds keep all outputs local.
providers.gradleProperty("povBuildRoot").orNull?.let { layout.buildDirectory.set(file(it)) }

android {
    namespace = "dev.pov.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pov.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.0-preview"
        manifestPlaceholders["cameoAppLabel"] = "Cameo"
        testInstrumentationRunner = "dev.pov.tv.PlaybackAuditInstrumentation"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        release { isMinifyEnabled = false }
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".cameopreview"
            versionNameSuffix = "-side-by-side"
            manifestPlaceholders["cameoAppLabel"] = "Cameo Preview"
            matchingFallbacks += listOf("debug")
        }
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Embed the engine; the separate VLC application is not required.
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-exoplayer-dash:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
}
