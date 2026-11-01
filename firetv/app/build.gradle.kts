import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Secrets come only from env vars (CI) or ~/.gradle/gradle.properties (local), never a repo file.
fun secret(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

val serverUrl = secret("IPTV_SERVER_URL") ?: ""
val appToken = secret("IPTV_APP_TOKEN") ?: ""
val keystoreFile = secret("IPTV_KEYSTORE_FILE")

// Same number for a local build and the CI build of one commit; later commits are always higher (KTD11).
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

android {
    namespace = "com.mohamed3on.iptv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mohamed3on.iptv"
        minSdk = 30
        targetSdk = 36
        versionCode = commitCount
        versionName = "1.$commitCount"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = secret("IPTV_KEYSTORE_PASSWORD")
                keyAlias = secret("IPTV_KEY_ALIAS")
                keyPassword = secret("IPTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The server's domain can't live in the public repo, so the network security config is
// generated under build/: cleartext allowed (provider streams redirect to http-only nodes)
// except for the server's own host (KTD10).
abstract class NetworkSecurityConfigTask : DefaultTask() {
    @get:Input abstract val serverUrl: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val host = serverUrl.get().takeIf { it.isNotEmpty() }?.let { URI(it).host }
        val serverDomain = host?.let {
            """
            |    <domain-config cleartextTrafficPermitted="false">
            |        <domain includeSubdomains="true">$it</domain>
            |    </domain-config>
            |""".trimMargin()
        } ?: ""
        val file = outputDir.file("xml/network_security_config.xml").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="true" />
            |$serverDomain</network-security-config>
            |""".trimMargin()
        )
    }
}

androidComponents {
    onVariants { variant ->
        val url = serverUrl
        val task = tasks.register<NetworkSecurityConfigTask>("generate${variant.name.replaceFirstChar(Char::uppercase)}NetworkSecurityConfig") {
            serverUrl.set(url)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, NetworkSecurityConfigTask::outputDir)
    }
}

// Checked only when a release APK is packaged, so unit tests can run with no secrets (KTD14).
tasks.matching { it.name == "packageRelease" }.configureEach {
    val url = serverUrl
    doFirst {
        if (!url.startsWith("https://") || URI(url).host.isNullOrEmpty()) {
            throw GradleException("IPTV_SERVER_URL must be an https:// URL (set it as an env var or in ~/.gradle/gradle.properties)")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
}
