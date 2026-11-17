plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.ktor.noop"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":android:peephole-core-noop"))
    api(libs.ktor.client.core)
}

mavenPublishing {
    coordinates(artifactId = "peephole-ktor-noop")
    pom {
        name.set("Peephole Ktor (no-op)")
        description.set("No-op Peephole Ktor integration for release builds.")
    }
}
