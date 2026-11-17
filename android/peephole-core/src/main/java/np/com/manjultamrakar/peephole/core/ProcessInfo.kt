package np.com.manjultamrakar.peephole.core

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

internal object ProcessInfo {
    fun pid(): Int = try {
        Process.myPid()
    } catch (_: Throwable) {
        0
    }

    fun processName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        try {
            val cmdline = File("/proc/self/cmdline").readBytes()
            val end = cmdline.indexOf(0.toByte()).let { if (it < 0) cmdline.size else it }
            val name = String(cmdline, 0, end).trim()
            if (name.isNotEmpty()) return name
        } catch (_: Exception) {
        }
        return context.packageName
    }
}
