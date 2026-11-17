package np.com.manjultamrakar.peephole.ide.adb

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import np.com.manjultamrakar.peephole.protocol.SOCKET_PREFIX
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

data class AdbDevice(
    val serial: String,
    /** `device`, `offline`, `unauthorized`, ... */
    val state: String,
    val model: String?,
) {
    val isOnline: Boolean get() = state == "device"
}

data class DeviceDetails(val manufacturer: String?, val model: String?, val release: String?, val sdk: String?, val avdName: String?)

/** Thin wrapper around the adb CLI. All calls block; never call on the EDT. */
class AdbClient(val adb: File) {

    fun run(vararg args: String, timeoutMs: Int = 5000): ProcessOutput {
        val cmd = GeneralCommandLine(listOf(adb.absolutePath) + args).withCharset(StandardCharsets.UTF_8)
        return CapturingProcessHandler(cmd).runProcess(timeoutMs)
    }

    fun devices(): List<AdbDevice> {
        val out = run("devices", "-l")
        if (out.exitCode != 0 || out.isTimeout) throw IOException("adb devices failed: ${out.stderr.ifBlank { out.stdout }.trim()}")
        return parseDevices(out.stdout)
    }

    fun details(serial: String): DeviceDetails {
        val props = listOf("ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
        val out = run("-s", serial, "shell", props.joinToString("; ") { "getprop $it" })
        val lines = out.stdout.lines().map { it.trim() }
        fun at(i: Int) = lines.getOrNull(i)?.takeIf { it.isNotEmpty() }
        return DeviceDetails(at(0), at(1), at(2), at(3), at(4) ?: at(5))
    }

    /** Process names of apps on [serial] that have the inspector socket open. */
    fun inspectableProcesses(serial: String): List<String> {
        val out = run("-s", serial, "shell", "cat", "/proc/net/unix")
        if (out.exitCode != 0 || out.isTimeout) return emptyList()
        return parseInspectorSockets(out.stdout)
    }

    /** Forwards a free local TCP port to the app's abstract socket and returns the port. */
    fun forward(serial: String, socketName: String): Int {
        val out = run("-s", serial, "forward", "tcp:0", "localabstract:$socketName")
        val port = out.stdout.trim().lines().lastOrNull()?.trim()?.toIntOrNull()
        if (out.exitCode != 0 || port == null) throw IOException("adb forward failed: ${out.stderr.ifBlank { out.stdout }.trim()}")
        return port
    }

    fun removeForward(serial: String, port: Int) {
        try {
            run("-s", serial, "forward", "--remove", "tcp:$port", timeoutMs = 3000)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun parseDevices(output: String): List<AdbDevice> = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val model = parts.drop(2).firstOrNull { it.startsWith("model:") }?.removePrefix("model:")
                AdbDevice(parts[0], parts[1], model)
            }
            .toList()

        fun parseInspectorSockets(procNetUnix: String): List<String> = procNetUnix.lineSequence()
            .mapNotNull { line ->
                val path = line.trim().substringAfterLast(' ', "")
                if (path.startsWith("@$SOCKET_PREFIX")) path.removePrefix("@$SOCKET_PREFIX") else null
            }
            .distinct()
            .sorted()
            .toList()
    }
}
