plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.core.noop"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":protocol"))
}

mavenPublishing {
    coordinates(artifactId = "peephole-core-noop")
    pom {
        name.set("Peephole Core (no-op)")
        description.set("No-op Peephole core for release builds.")
    }
}
