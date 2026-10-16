plugins {
    id("com.android.library")
}

android {
    namespace = "com.carlink.usbbridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 32
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
