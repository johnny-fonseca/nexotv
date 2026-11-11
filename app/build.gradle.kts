import org.gradle.api.tasks.compile.JavaCompile

plugins {
    id("com.android.application")
}

val releaseSigningValues = listOf(
    providers.environmentVariable("TVIPTV_RELEASE_STORE_FILE").orNull,
    providers.environmentVariable("TVIPTV_RELEASE_STORE_PASSWORD").orNull,
    providers.environmentVariable("TVIPTV_RELEASE_KEY_ALIAS").orNull,
    providers.environmentVariable("TVIPTV_RELEASE_KEY_PASSWORD").orNull,
)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
if (releaseSigningValues.any { !it.isNullOrBlank() } && !hasReleaseSigning) {
    throw GradleException("Set all four TVIPTV_RELEASE_* signing variables to build a signed release.")
}

android {
    namespace = "Nexotv.iptv.player"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildFeatures { buildConfig = true }

    flavorDimensions += "marca"
    productFlavors {
        create("teletica") {
            dimension = "marca"
            // Conserva el ID publicado para que la app Teletica pueda actualizarse.
            applicationId = "teletica.iptv.player"
            buildConfigField("String", "BRAND_NAME", "\"Teletica\"")
            buildConfigField("String", "CHANNEL_NAME", "\"Canal 7\"")
            buildConfigField("String", "BRAND_KEY", "\"teletica\"")
            buildConfigField("String", "DEFAULT_EPG_URL", "\"https://www.teletica.com/api/schedule/1\"")
            buildConfigField("String", "OFFICIAL_EPG_HOSTS", "\"teletica.com,www.teletica.com\"")
            buildConfigField("String", "XMLTV_CHANNEL_MARKERS", "\"teletica,canal.7,canal 7,canal-7,teletica7,7_de_costa_rica,7\"")
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"teletica\"")
            manifestPlaceholders["deepLinkScheme"] = "teletica"
            buildConfigField("String", "PREFERENCES_FILE", "\"teletica_player\"")
            buildConfigField("String", "RECORDING_PREFIX", "\"Teletica-\"")
            buildConfigField(
                "String",
                "DEFAULT_STREAM_URL",
                "\"http://190.61.101.11:7050/play/a07t/index.m3u8\"",
            )
        }
        create("futv") {
            dimension = "marca"
            // ID independiente para instalar FUTV junto con la variante Teletica.
            applicationId = "teletica.iptv.player.futv"
            buildConfigField("String", "BRAND_NAME", "\"FUTV\"")
            buildConfigField("String", "CHANNEL_NAME", "\"FUTV\"")
            buildConfigField("String", "BRAND_KEY", "\"futv\"")
            buildConfigField("String", "DEFAULT_EPG_URL", "\"https://futvcr.com/\"")
            buildConfigField("String", "OFFICIAL_EPG_HOSTS", "\"\"")
            buildConfigField("String", "XMLTV_CHANNEL_MARKERS", "\"futv\"")
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"futv\"")
            manifestPlaceholders["deepLinkScheme"] = "futv"
            buildConfigField("String", "PREFERENCES_FILE", "\"futv_player\"")
            buildConfigField("String", "RECORDING_PREFIX", "\"FUTV-\"")
            buildConfigField(
                "String",
                "DEFAULT_STREAM_URL",
                "\"http://190.61.90.17:40000/play/a03v/index.m3u8\"",
            )
        }
        create("opa") {
            dimension = "marca"
            applicationId = "teletica.iptv.player.opa"
            buildConfigField("String", "BRAND_NAME", "\"¡OPA!\"")
            buildConfigField("String", "CHANNEL_NAME", "\"Canal 38\"")
            buildConfigField("String", "BRAND_KEY", "\"opa\"")
            buildConfigField("String", "DEFAULT_EPG_URL", "\"https://genteopa.com/programas/\"")
            buildConfigField("String", "OFFICIAL_EPG_HOSTS", "\"\"")
            buildConfigField("String", "XMLTV_CHANNEL_MARKERS", "\"opa,canal.38,canal 38,canal38\"")
            buildConfigField("String", "DEEP_LINK_SCHEME", "\"opa\"")
            manifestPlaceholders["deepLinkScheme"] = "opa"
            buildConfigField("String", "PREFERENCES_FILE", "\"opa_player\"")
            buildConfigField("String", "RECORDING_PREFIX", "\"OPA-\"")
            buildConfigField(
                "String",
                "DEFAULT_STREAM_URL",
                "\"https://5fc584f3f19c9.streamlock.net/genteopa/videogenteopa_360p/playlist.m3u8\"",
            )
        }
    }

    defaultConfig {
        applicationId = "Nexotv.iptv.player"
        minSdk = 23
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues[0]!!)
                storePassword = releaseSigningValues[1]!!
                keyAlias = releaseSigningValues[2]!!
                keyPassword = releaseSigningValues[3]!!
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Keep explicit javac forking disabled. The Android Gradle Plugin may still use
// a process-isolated Worker Daemon; this setting does not bypass loopback policy.
tasks.withType<JavaCompile>().configureEach {
    options.isFork = false
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("org.tukaani:xz:1.12")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jsoup:jsoup:1.23.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    description = "Validate the release signing configuration and keystore."
    doLast {
        if (!hasReleaseSigning) {
            throw GradleException(
                "A release build requires TVIPTV_RELEASE_STORE_FILE, TVIPTV_RELEASE_STORE_PASSWORD, " +
                    "TVIPTV_RELEASE_KEY_ALIAS and TVIPTV_RELEASE_KEY_PASSWORD.",
            )
        }
        val keystore = file(releaseSigningValues[0]!!)
        if (!keystore.isFile) throw GradleException("Release keystore not found: ${keystore.absolutePath}")
    }
}

tasks.matching { Regex("^(assemble|bundle|package)(.*)Release$").matches(it.name) }.configureEach {
    dependsOn(verifyReleaseSigning)
}
