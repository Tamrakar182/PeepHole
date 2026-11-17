pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "peephole"

include(":protocol")
include(":android:peephole-core")
include(":android:peephole-okhttp")
include(":android:peephole-ktor")
include(":android:peephole-core-noop")
include(":android:peephole-okhttp-noop")
include(":android:peephole-ktor-noop")
include(":plugin")
include(":sample")
