plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "np.com.manjultamrakar.peephole.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "np.com.manjultamrakar.peephole.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.okhttp5)
    implementation(libs.ktor.client.cio)

    // Real inspector in debug builds, no-op in release builds.
    debugImplementation(project(":android:peephole-okhttp"))
    debugImplementation(project(":android:peephole-ktor"))
    releaseImplementation(project(":android:peephole-okhttp-noop"))
    releaseImplementation(project(":android:peephole-ktor-noop"))
}
