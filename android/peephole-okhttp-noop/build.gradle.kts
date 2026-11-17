plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.okhttp.noop"
    compileSdk = 36
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":android:peephole-core-noop"))
    api(libs.okhttp)
}

mavenPublishing {
    coordinates(artifactId = "peephole-okhttp-noop")
    pom {
        name.set("Peephole OkHttp (no-op)")
        description.set("No-op Peephole OkHttp integration for release builds.")
    }
}
