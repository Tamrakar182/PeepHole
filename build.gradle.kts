import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
    alias(libs.plugins.vanniktech.publish) apply false
}

allprojects {
    group = "np.com.manjultamrakar"
    version = providers.gradleProperty("peepholeVersion").get()
}

// Shared Maven Central metadata for every published library module.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            // Central requires signatures. Sign whenever a key is configured (in-memory key or local gpg),
            // so publishToMavenLocal still works on machines without one.
            val hasSigningKey = listOf("signingInMemoryKey", "signing.keyId", "signing.gnupg.keyName")
                .any { providers.gradleProperty(it).isPresent }
            if (hasSigningKey) signAllPublications()
            pom {
                inceptionYear.set("2026")
                url.set("https://github.com/Tamrakar182/PeepHole")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("Tamrakar182")
                        name.set("Manjul Tamrakar")
                        url.set("https://manjultamrakar.com.np")
                    }
                }
                scm {
                    url.set("https://github.com/Tamrakar182/PeepHole")
                    connection.set("scm:git:https://github.com/Tamrakar182/PeepHole.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Tamrakar182/PeepHole.git")
                }
            }
        }
    }
}
