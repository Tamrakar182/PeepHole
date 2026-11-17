plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
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
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":protocol"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "peephole-core"
            }
        }
    }
}
