plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.ktor"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":android:peephole-core"))
    api(libs.ktor.client.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.okhttp5.mockwebserver)
}

mavenPublishing {
    coordinates(artifactId = "peephole-ktor")
    pom {
        name.set("Peephole Ktor")
        description.set("Ktor client plugin that streams REST, GraphQL and WebSocket traffic to the Peephole Android Studio plugin.")
    }
}
