plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "np.com.manjultamrakar.peephole.okhttp.noop"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":android:peephole-core-noop"))
    api(libs.okhttp)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "peephole-okhttp-noop"
            }
        }
    }
}
