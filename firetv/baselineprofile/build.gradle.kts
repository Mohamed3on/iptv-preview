import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Generates the app's baseline profile, and benchmarks it, on a Gradle-managed emulator: Gradle starts
// its own headless one, so a Fire TV stick connected over adb is never used. Run via generate.sh,
// which also starts the fixture server the app talks to here.
android {
    namespace = "com.mohamed3on.iptv.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator frame timings are indicative only; let the benchmarks run there anyway.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.allDevices {
        // A 1080p TV screen, like the stick's (960×540 dp), on the API 34 Google APIs image.
        create<ManagedVirtualDevice>("tvApi34") {
            device = "Television (1080p)"
            apiLevel = 34
            systemImageSource = "google"
        }
    }
}

baselineProfile {
    managedDevices += "tvApi34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
