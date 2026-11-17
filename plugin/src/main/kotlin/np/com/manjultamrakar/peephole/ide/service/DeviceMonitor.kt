package np.com.manjultamrakar.peephole.ide.service

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import np.com.manjultamrakar.peephole.ide.adb.AdbClient
import np.com.manjultamrakar.peephole.ide.adb.AdbDevice
import np.com.manjultamrakar.peephole.ide.adb.AdbLocator
import np.com.manjultamrakar.peephole.ide.adb.DeviceDetails
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class DeviceState(
    val device: AdbDevice,
    val details: DeviceDetails?,
    /** Process names with an open inspector socket. */
    val apps: List<String>,
) {
    val serial: String get() = device.serial

    val displayName: String
        get() {
            val d = details
            val name = when {
                d?.avdName != null -> d.avdName.replace('_', ' ')
                d?.model != null -> listOfNotNull(d.manufacturer?.replaceFirstChar { it.uppercase() }, d.model)
                    .distinct().joinToString(" ")
                device.model != null -> device.model.replace('_', ' ')
                else -> device.serial
            }
            val api = d?.release?.let { " · Android $it" } ?: ""
            return "$name$api"
        }
}

data class DeviceSnapshot(
    val adb: File?,
    val devices: List<DeviceState>,
    val error: String? = null,
)

/** Polls adb for devices and inspectable app sockets. */
class DeviceMonitor(
    private val project: Project,
    private val onSnapshot: (DeviceSnapshot) -> Unit,
) {
    private val log = logger<DeviceMonitor>()
    private val executor = AppExecutorUtil.createBoundedScheduledExecutorService("Peephole device monitor", 1)
    private val detailsCache = ConcurrentHashMap<String, DeviceDetails>()
    private var future: ScheduledFuture<*>? = null

    @Volatile
    var fast: Boolean = true

    @Volatile
    private var stopped = false
    private var lastPoll = 0L

    fun start() {
        future = executor.scheduleWithFixedDelay({ tick() }, 0, 1, TimeUnit.SECONDS)
    }

    /** Poll immediately (e.g. after the user hits refresh). */
    fun refreshNow() {
        lastPoll = 0
        executor.execute { tick() }
    }

    fun stop() {
        stopped = true
        future?.cancel(false)
        executor.shutdownNow()
    }

    private fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        val interval = if (fast) 2_000 else 8_000
        if (now - lastPoll < interval) return
        lastPoll = now
        onSnapshot(poll())
    }

    private fun poll(): DeviceSnapshot {
        val adbFile = AdbLocator.find(project)
            ?: return DeviceSnapshot(null, emptyList(), "ADB not found. Set its path in Settings | Tools | Peephole.")
        val adb = AdbClient(adbFile)
        return try {
            val devices = adb.devices()
            val states = devices.map { device ->
                if (!device.isOnline) {
                    DeviceState(device, detailsCache[device.serial], emptyList())
                } else {
                    val details = detailsCache[device.serial] ?: runCatching { adb.details(device.serial) }.getOrNull()
                        ?.also { detailsCache[device.serial] = it }
                    DeviceState(device, details, runCatching { adb.inspectableProcesses(device.serial) }.getOrDefault(emptyList()))
                }
            }
            DeviceSnapshot(adbFile, states)
        } catch (e: Exception) {
            log.debug("adb poll failed", e)
            DeviceSnapshot(adbFile, emptyList(), e.message ?: e.toString())
        }
    }
}
