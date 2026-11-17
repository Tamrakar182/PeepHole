plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
}

allprojects {
    group = "np.com.manjultamrakar"
    version = providers.gradleProperty("peepholeVersion").get()
}
