plugins {
    id("com.android.application")
}

// ─────────────────────────────────────────────────────────────────────────────
// The GM AAOS fixed-handler USB owner. See the USB permission split section in OCBMANDROID.md.
//
// GM's Y181 image ships `config_UsbDeviceConnectionHandling_component =
// android.car.usb.handler/android.car.usb.handler.UsbHostManagementActivity` but STRIPS the
// package, so `deviceAttachedForFixedHandler` throws NameNotFoundException and the third-party
// implicit-grant path dead-ends → the permission dialog reappears on every attach.
//
// This module installs a /data app UNDER that exact package name. When present in the foreground
// user (user 10), the framework resolves THIS app as the fixed handler and calls
// `grantDevicePermission(device, ourUid)` SILENTLY on every attach — no dialog, no root.
//
// This APK holds the granted device permission and owns the raw USB pipe. It stays separate from
// the daily-driver `:app` (zeno.carlink.ocbm), which only binds through :usbbridge.
//
// Structure mirrors AOSP `packages/services/Car/car-usb-handler`; divergences (NoDisplay instead of
// the Dialog picker, no MANAGE_USB) are called out in the manifest — a /data untrusted_app cannot
// hold MANAGE_USB and does not need it here.
// ─────────────────────────────────────────────────────────────────────────────

android {
    // MUST equal the stripped package GM points its fixed-handler config at. This is the entire
    // mechanism — a different applicationId is just an ordinary app and gets the dialog.
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
        // No Compose, no BuildConfig, no AndroidX — the proof uses only platform classes
        // (android.app.Activity, android.hardware.usb.*, android.util.Log) so the APK is tiny and
        // the build has nothing to break.
        buildConfig = false
    }
}

dependencies {
    implementation(project(":usbbridge"))
}
