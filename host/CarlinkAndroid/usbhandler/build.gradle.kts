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
    namespace = "com.carlink.usbhelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "android.car.usb.handler"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0-usbbridge"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(project(":usbbridge"))
}
