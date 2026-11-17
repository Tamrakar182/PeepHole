package np.com.manjultamrakar.peephole.ide.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class InspectorConfigurable : BoundConfigurable("Peephole") {
    override fun createPanel(): DialogPanel {
        val s = InspectorSettings.getInstance().state
        return panel {
            group("ADB") {
                row("ADB executable:") {
                    textField()
                        .columns(COLUMNS_LARGE)
                        .bindText({ s.adbPath ?: "" }, { s.adbPath = it.trim().ifEmpty { null } })
                        .comment("Leave empty to use Android Studio's SDK, then \$ANDROID_HOME, then the default SDK location.")
                }
            }
            group("Tool Window") {
                row("Calls kept per app:") {
                    intTextField(100..100_000).bindIntText({ s.maxEntries }, { s.maxEntries = it })
                }
                row {
                    checkBox("Pretty-print JSON bodies").bindSelected({ s.prettyPrintJson }, { s.prettyPrintJson = it })
                }
                row {
                    checkBox("Connect automatically when an inspectable app starts")
                        .bindSelected({ s.autoConnect }, { s.autoConnect = it })
                }
                row {
                    checkBox("Scroll to new calls").bindSelected({ s.autoScroll }, { s.autoScroll = it })
                }
            }
            group("App-Side Options") {
                row {
                    text(
                        "Logcat output (off by default), body size limits and header redaction are configured in the app:<br>" +
                            "<code>Peephole.init(context, PeepholeConfig(logToLogcat = true))</code>",
                    )
                }
            }
        }
    }
}
