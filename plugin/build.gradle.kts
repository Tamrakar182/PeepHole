import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":protocol"))
    intellijPlatform {
        // Compile against / run in the locally installed Android Studio (see `studioPath` in gradle.properties).
        local(providers.gradleProperty("studioPath").get())
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// The IDE ships the Kotlin stdlib; never bundle another copy.
configurations.named("runtimeClasspath") {
    exclude(group = "org.jetbrains.kotlin")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        id = "np.com.manjultamrakar.peephole"
        name = "Peephole"
        version = project.version.toString()
        // Shown on the plugin's "What's New" tab. Add a section on top for each release.
        changeNotes = """
            <h3>0.1.0 — First release</h3>
            <ul>
              <li><b>Live traffic in the IDE</b> for OkHttp (incl. Retrofit and Apollo Kotlin) and Ktor, over an adb-forwarded socket. Logcat stays clean.</li>
              <li><b>REST, GraphQL and WebSocket</b>: GraphQL operations are detected and named; WebSocket messages are listed per connection, including GraphQL-over-WebSocket.</li>
              <li><b>Multiple devices and apps</b>: pick any connected device and any process running the library.</li>
              <li><b>Readable bodies</b>: JSON and GraphQL are pretty-printed with collapsible blocks; record-separated (SignalR) WebSocket payloads are split into JSON.</li>
              <li><b>Copy anything</b>: body, full request, full response, URL, or the request as cURL.</li>
              <li><b>Filters</b> by type, status and text; pause recording; follow new calls automatically.</li>
              <li><b>Export to HAR</b> for Chrome DevTools, Charles or Proxyman.</li>
              <li>No-op artifacts for release builds, so nothing ships in production.</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}

// Android Studio 2025.3 runs on Java 21; keep bytecode at 21 even though the local Studio 2026.2 runs on 25.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("peephole")
}
