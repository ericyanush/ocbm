plugins {
    id("com.android.library")
}

android {
    namespace = "com.carlink.usbbridge"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
