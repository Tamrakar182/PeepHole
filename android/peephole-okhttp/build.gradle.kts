plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.publish)
}

android {
    namespace = "np.com.manjultamrakar.peephole.okhttp"
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
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp.mockwebserver)
}

mavenPublishing {
    coordinates(artifactId = "peephole-okhttp")
    pom {
        name.set("Peephole OkHttp")
        description.set("OkHttp interceptor and WebSocket factory that stream REST, GraphQL and WebSocket traffic to the Peephole Android Studio plugin.")
    }
}

// Verify binary compatibility with newer OkHttp: ./gradlew :android:peephole-okhttp:testDebugUnitTest -PokhttpTestVersion=5.3.2
providers.gradleProperty("okhttpTestVersion").orNull?.let { v ->
    configurations.matching { it.name.contains("UnitTest") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.squareup.okhttp3") useVersion(v)
        }
    }
}
