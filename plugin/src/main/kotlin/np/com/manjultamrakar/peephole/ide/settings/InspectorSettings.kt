package np.com.manjultamrakar.peephole.ide.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "PeepholeSettings", storages = [Storage("peephole.xml")])
class InspectorSettings : SimplePersistentStateComponent<InspectorSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        /** Explicit adb executable (or SDK directory); empty = auto-detect. */
        var adbPath by string()
        var maxEntries by property(2000)
        var prettyPrintJson by property(true)
        var autoConnect by property(true)
        var autoScroll by property(true)
        var lastSerial by string()
        var lastProcess by string()
    }

    companion object {
        fun getInstance(): InspectorSettings = service()
    }
}
