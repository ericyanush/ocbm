plugins {
    id("com.android.application")
}

// ─────────────────────────────────────────────────────────────────────────────
// The GM AAOS fixed-handler USB owner. See the USB permission split section in OCBMANDROID.md.
//
// This module installs a /data app under GM's fixed-handler package name. On the target truck,
// the framework silently grants that package USB permission on attach. The configured activity is
// intentionally omitted: original Carlink testing showed the grant still works, while declaring
// the activity triggered driving distraction overlays.
//
// This APK holds the granted device permission and owns the raw USB pipe. It stays separate from
// the daily-driver `:app` (zeno.carlink.ocbm), which only binds through :usbbridge.
//
// The exported bridge service owns USB I/O. No activity, attach receiver, or MANAGE_USB permission
// is required; a /data untrusted_app cannot hold MANAGE_USB and does not need it here.
// ─────────────────────────────────────────────────────────────────────────────

android {
    // MUST equal the package GM's fixed-handler path grants. The bridge service uses that grant;
    // the configured activity itself is deliberately absent to avoid the GM distraction overlay.
    namespace = "android.car.usb.handler"
    compileSdk = 37

    defaultConfig {
        applicationId = "android.car.usb.handler"
        minSdk = 32 // GM gminfo37 = Android 12L / API 32
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-usbbridge"
    }

    buildTypes {
        // Keep helper diagnostics and USB service names readable in test builds.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        // No Compose, no BuildConfig, no AndroidX — the helper uses platform USB/service APIs.
        buildConfig = false
    }
}

dependencies {
    implementation(project(":usbbridge"))
}
