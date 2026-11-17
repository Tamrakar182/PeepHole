import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

mavenPublishing {
    coordinates(artifactId = "peephole-protocol")
    pom {
        name.set("Peephole Protocol")
        description.set("Wire protocol shared by the Peephole Android libraries and the Peephole IDE plugin.")
    }
}
