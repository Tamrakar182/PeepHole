plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "LIBRARY_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

mavenPublishing {
    coordinates(artifactId = "peephole-core")
    pom {
        name.set("Peephole Core")
        description.set("Capture engine and adb socket server for the Peephole Android Studio network inspector.")
    }
}
