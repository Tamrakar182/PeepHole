package np.com.manjultamrakar.peephole

import android.content.Context
import np.com.manjultamrakar.peephole.protocol.InspectorEvent

/** No-op variant for release builds: same API as the real inspector, does nothing. */
object Peephole {
    @Volatile
    @JvmStatic
    var config: PeepholeConfig = PeepholeConfig(enabled = false)

    @JvmStatic
    @JvmOverloads
    fun init(context: Context, config: PeepholeConfig = this.config) = Unit

    @JvmStatic
    fun shutdown() = Unit

    @JvmStatic
    val isEnabled: Boolean get() = false

    @JvmStatic
    fun newId(): String = ""

    @JvmStatic
    fun addEventListener(listener: (InspectorEvent) -> Unit) = Unit

    @JvmStatic
    fun removeEventListener(listener: (InspectorEvent) -> Unit) = Unit

    @JvmStatic
    fun clear() = Unit

    @JvmStatic
    fun emit(event: InspectorEvent) = Unit
}
