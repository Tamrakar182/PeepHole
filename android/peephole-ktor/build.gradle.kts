plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
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
    publishing { singleVariant("release") { withSourcesJar() } }
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

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "peephole-ktor"
            }
        }
    }
}
