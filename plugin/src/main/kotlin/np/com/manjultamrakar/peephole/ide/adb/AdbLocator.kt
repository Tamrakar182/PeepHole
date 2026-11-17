package np.com.manjultamrakar.peephole.ide.adb

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import np.com.manjultamrakar.peephole.ide.settings.InspectorSettings
import java.io.File
import java.util.Properties

/**
 * Finds the adb executable, preferring the one Android Studio itself uses so we never start a
 * second, version-mismatched adb server.
 */
object AdbLocator {

    fun find(project: Project?): File? {
        InspectorSettings.getInstance().state.adbPath?.takeIf { it.isNotBlank() }?.let { path ->
            val f = File(path)
            return if (f.isDirectory) adbIn(f) ?: adbIn(File(f, "platform-tools")) else f.takeIf { it.canExecute() }
        }
        studioSdkDir()?.let { adbInSdk(it) }?.let { return it }
        for (env in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            System.getenv(env)?.let { adbInSdk(File(it)) }?.let { return it }
        }
        project?.basePath?.let { localPropertiesSdk(File(it)) }?.let { adbInSdk(it) }?.let { return it }
        val home = System.getProperty("user.home")
        val defaults = when {
            SystemInfo.isMac -> listOf("$home/Library/Android/sdk")
            SystemInfo.isWindows -> listOfNotNull(System.getenv("LOCALAPPDATA")?.let { "$it\\Android\\Sdk" })
            else -> listOf("$home/Android/Sdk", "$home/Android/sdk")
        }
        defaults.firstNotNullOfOrNull { adbInSdk(File(it)) }?.let { return it }
        return System.getenv("PATH")?.split(File.pathSeparator)?.firstNotNullOfOrNull { adbIn(File(it)) }
    }

    private fun adbInSdk(sdk: File): File? = adbIn(File(sdk, "platform-tools"))

    private fun adbIn(dir: File): File? {
        val f = File(dir, if (SystemInfo.isWindows) "adb.exe" else "adb")
        return f.takeIf { it.isFile && it.canExecute() }
    }

    /** Android Studio's configured SDK, read reflectively so the plugin has no hard dependency on the Android plugin. */
    private fun studioSdkDir(): File? = try {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.android"))
        val loader = plugin?.pluginClassLoader
        if (loader == null) null else {
            val cls = Class.forName("com.android.tools.idea.sdk.IdeSdks", true, loader)
            val instance = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("getAndroidSdkPath").invoke(instance) as? File
        }
    } catch (_: Throwable) {
        null
    }

    private fun localPropertiesSdk(projectDir: File): File? = try {
        val file = File(projectDir, "local.properties")
        if (!file.isFile) null else {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.getProperty("sdk.dir")?.let { File(it) }
        }
    } catch (_: Exception) {
        null
    }
}
