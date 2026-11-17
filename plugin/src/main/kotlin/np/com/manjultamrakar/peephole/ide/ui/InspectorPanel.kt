package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import np.com.manjultamrakar.peephole.ide.export.CurlExporter
import np.com.manjultamrakar.peephole.ide.export.HarExporter
import np.com.manjultamrakar.peephole.ide.export.HttpTextExporter
import np.com.manjultamrakar.peephole.ide.model.EntryKind
import np.com.manjultamrakar.peephole.ide.model.HttpEntry
import np.com.manjultamrakar.peephole.ide.model.NetworkEntry
import np.com.manjultamrakar.peephole.ide.model.SessionKey
import np.com.manjultamrakar.peephole.ide.model.StoreChanges
import np.com.manjultamrakar.peephole.ide.model.WsEntry
import np.com.manjultamrakar.peephole.ide.service.ConnectionState
import np.com.manjultamrakar.peephole.ide.service.ConnectionStatus
import np.com.manjultamrakar.peephole.ide.service.DeviceSnapshot
import np.com.manjultamrakar.peephole.ide.service.InspectorProjectService
import np.com.manjultamrakar.peephole.ide.settings.InspectorConfigurable
import np.com.manjultamrakar.peephole.ide.settings.InspectorSettings
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SortOrder
import javax.swing.event.DocumentEvent
import javax.swing.table.TableRowSorter

private data class DeviceItem(val serial: String, val label: String, val online: Boolean, val appCount: Int)
private data class AppItem(val processName: String, val running: Boolean)

private enum class StatusFilter(val label: String) {
    ALL("All statuses"), SUCCESS("2xx"), REDIRECT("3xx"), CLIENT("4xx"), SERVER("5xx"), ERRORS("Errors (4xx, 5xx, failed)"), PENDING("Pending / live");

    override fun toString() = label
}

internal class InspectorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val service = project.service<InspectorProjectService>()
    private val settings get() = InspectorSettings.getInstance().state

    private val deviceCombo = ComboBox<DeviceItem>().apply {
        renderer = object : SimpleListCellRenderer<DeviceItem>() {
            override fun customize(list: JList<out DeviceItem>, item: DeviceItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = when {
                    item == null -> "No devices"
                    !item.online -> "${item.label}  (offline)"
                    item.appCount > 0 -> "${item.label}  ·  ${item.appCount} app${if (item.appCount == 1) "" else "s"}"
                    else -> item.label
                }
            }
        }
        prototypeDisplayValue = DeviceItem("", "Pixel 8 Pro API 35 · Android 15  ·  2 apps", true, 0)
    }
    private val appCombo = ComboBox<AppItem>().apply {
        renderer = object : SimpleListCellRenderer<AppItem>() {
            override fun customize(list: JList<out AppItem>, item: AppItem?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = when {
                    item == null -> "No inspectable apps"
                    item.running -> item.processName
                    else -> "${item.processName}  (not running)"
                }
            }
        }
        prototypeDisplayValue = AppItem("com.example.application.debug", true)
    }
    private val statusLabel = JBLabel().apply { border = JBUI.Borders.emptyLeft(8) }
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Filter by URL, operation, status or body" }
    private val restBox = JBCheckBox("REST", true)
    private val graphqlBox = JBCheckBox("GraphQL", true)
    private val wsBox = JBCheckBox("WebSocket", true)
    private val statusFilter = ComboBox(StatusFilter.entries.toTypedArray())

    private val model = EntriesTableModel()
    private val sorter = TableRowSorter(model)
    private val table = JBTable(model).apply {
        rowSorter = sorter
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoCreateRowSorter = false
        val renderer = EntryCellRenderer(this@InspectorPanel.model)
        Column.entries.forEachIndexed { i, c ->
            columnModel.getColumn(i).preferredWidth = c.width
            columnModel.getColumn(i).cellRenderer = renderer
        }
    }
    private val details = DetailsPanel(project)

    private var updatingCombos = false
    private var shownKey: SessionKey? = null

    init {
        val selectionRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(JBLabel("Device:"))
            add(deviceCombo)
            add(JBLabel("App:").apply { border = JBUI.Borders.emptyLeft(6) })
            add(appCombo)
            add(statusLabel)
        }
        val filterRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(search.apply { preferredSize = JBUI.size(320, preferredSize.height) })
            add(restBox)
            add(graphqlBox)
            add(wsBox)
            add(statusFilter)
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("Peephole", createActions(), true)
        toolbar.targetComponent = this
        val secondRow = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(filterRow, BorderLayout.CENTER)
        }
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.customLineBottom(JBColor.border())
            add(selectionRow.also { it.alignmentX = 0f })
            add(secondRow.also { it.alignmentX = 0f })
        }

        val splitter = OnePixelSplitter(false, 0.55f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(table, true)
            secondComponent = details
        }
        val content = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
        }
        setContent(content)

        PopupHandler.installPopupMenu(table, createContextMenu(), "PeepholePopup")
        table.selectionModel.addListSelectionListener { e -> if (!e.valueIsAdjusting) details.showEntry(selectedEntry()) }
        deviceCombo.addActionListener { if (!updatingCombos) onDeviceChosen() }
        appCombo.addActionListener { if (!updatingCombos) onAppChosen() }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        listOf(restBox, graphqlBox, wsBox).forEach { it.addActionListener { applyFilter() } }
        statusFilter.addActionListener { applyFilter() }

        service.addListener(object : InspectorProjectService.Listener {
            override fun devicesChanged(snapshot: DeviceSnapshot) = onDevices(snapshot)
            override fun connectionChanged(status: ConnectionStatus) = onStatus(status)
            override fun entriesChanged(changes: StoreChanges) = onEntries(changes)
        }, this)

        onDevices(service.snapshot)
        onStatus(service.status)
        showSession(service.currentKey)
    }

    fun setVisibleInIde(visible: Boolean) = service.setPollingFast(visible)

    // ---- device / app selection ----

    private fun onDevices(snapshot: DeviceSnapshot) {
        val current = service.currentKey
        val selectedSerial = (deviceCombo.selectedItem as? DeviceItem)?.serial ?: current?.serial ?: settings.lastSerial
        val items = snapshot.devices.map { DeviceItem(it.serial, it.displayName, it.device.isOnline, it.apps.size) }.toMutableList()
        // Keep the device we are attached to visible after it disconnects so its captured data stays reachable.
        if (current != null && items.none { it.serial == current.serial }) items += DeviceItem(current.serial, current.serial, false, 0)

        updatingCombos = true
        try {
            if (items != (0 until deviceCombo.itemCount).map { deviceCombo.getItemAt(it) }) {
                deviceCombo.removeAllItems()
                items.forEach { deviceCombo.addItem(it) }
            }
            val pick = items.firstOrNull { it.serial == selectedSerial }
                ?: items.firstOrNull { it.online && it.appCount > 0 }
                ?: items.firstOrNull()
            deviceCombo.selectedItem = pick
        } finally {
            updatingCombos = false
        }
        updateApps(snapshot)
        updateEmptyText()
    }

    private fun updateApps(snapshot: DeviceSnapshot = service.snapshot) {
        val device = deviceCombo.selectedItem as? DeviceItem
        val current = service.currentKey
        val running = snapshot.devices.firstOrNull { it.serial == device?.serial }?.apps.orEmpty()
        val items = running.map { AppItem(it, true) }.toMutableList()
        if (current != null && current.serial == device?.serial && running.none { it == current.processName }) {
            items.add(0, AppItem(current.processName, false))
        }
        val previous = appCombo.selectedItem as? AppItem
        updatingCombos = true
        try {
            if (items != (0 until appCombo.itemCount).map { appCombo.getItemAt(it) }) {
                appCombo.removeAllItems()
                items.forEach { appCombo.addItem(it) }
            }
            val preferred = when {
                current != null && current.serial == device?.serial -> current.processName
                previous != null -> previous.processName
                else -> settings.lastProcess
            }
            appCombo.selectedItem = items.firstOrNull { it.processName == preferred }
                ?: if (settings.autoConnect) items.firstOrNull { it.running } else null
        } finally {
            updatingCombos = false
        }
        onAppChosen()
    }

    private fun onDeviceChosen() = updateApps()

    private fun onAppChosen() {
        val device = deviceCombo.selectedItem as? DeviceItem
        val app = appCombo.selectedItem as? AppItem
        val key = if (device != null && app != null) SessionKey(device.serial, app.processName) else null
        if (key == null && service.currentKey != null && service.currentKey?.serial == device?.serial) return
        if (key != null && key != service.currentKey) {
            service.select(key)
            showSession(key)
        } else if (key == null && device != null && service.currentKey != null) {
            // Switched to a device without inspectable apps: show it empty, keep the old session in the store.
            service.select(null)
            showSession(null)
        }
        updateEmptyText()
    }

    private fun onStatus(status: ConnectionStatus) {
        val (text, color) = when (status.state) {
            ConnectionState.CONNECTED -> "● Connected${status.message?.let { " · $it" } ?: ""}" to Format.SUCCESS
            ConnectionState.CONNECTING -> "Connecting…" to JBColor.GRAY
            ConnectionState.WAITING -> "○ ${status.message ?: "Waiting for the app…"}" to JBColor.GRAY
            ConnectionState.INCOMPATIBLE -> "⚠ ${status.message}" to Format.SERVER_ERROR
            ConnectionState.CLOSED -> "" to JBColor.GRAY
        }
        statusLabel.text = text
        statusLabel.foreground = color
        updateEmptyText()
    }

    // ---- entries ----

    private fun showSession(key: SessionKey?) {
        shownKey = key
        val entries = key?.let { service.store.session(it).entries } ?: emptyList()
        model.setEntries(entries)
        if (sorter.sortKeys.isEmpty()) sorter.sortKeys = listOf(javax.swing.RowSorter.SortKey(Column.TIME.ordinal, SortOrder.ASCENDING))
        applyFilter()
        details.showEntry(selectedEntry())
        updateEmptyText()
    }

    private fun onEntries(changes: StoreChanges) {
        val key = shownKey ?: return
        val selected = selectedEntry()
        if (key in changes.reset) {
            model.setEntries(service.store.session(key).entries)
            reselect(selected)
        } else {
            val appended = changes.appended[key] ?: 0
            if (appended > 0) {
                // Decide before inserting: afterwards the previously-last row is no longer last.
                val follow = settings.autoScroll && (selected == null || isLastRowSelected())
                val size = model.rowCount
                model.fireTableRowsInserted(size - appended, size - 1)
                if (follow) scrollToEnd()
            }
            if (changes.updated[key]?.isNotEmpty() == true) {
                if (model.rowCount > 0) model.fireTableRowsUpdated(0, model.rowCount - 1)
                reselect(selected)
            }
        }
        selectedEntry()?.let { if (changes.updated[key]?.contains(it.id) == true) details.showEntry(it) }
        updateEmptyText()
    }

    private fun isLastRowSelected() = table.selectedRow == table.rowCount - 1

    private fun scrollToEnd() {
        // Defer until the table has been re-laid out with the new rows; otherwise the target
        // rect lies outside the table's current bounds and the scroll gets clamped short.
        javax.swing.SwingUtilities.invokeLater {
            val last = table.rowCount - 1
            if (last >= 0) table.scrollRectToVisible(table.getCellRect(last, 0, true))
        }
    }

    private fun selectedEntry(): NetworkEntry? {
        val viewRow = table.selectedRow
        if (viewRow < 0) return null
        return model.entryAt(table.convertRowIndexToModel(viewRow))
    }

    private fun reselect(entry: NetworkEntry?) {
        entry ?: return
        val modelRow = model.entries.indexOf(entry)
        if (modelRow < 0) return
        val viewRow = table.convertRowIndexToView(modelRow)
        if (viewRow >= 0 && table.selectedRow != viewRow) table.selectionModel.setSelectionInterval(viewRow, viewRow)
    }

    private fun applyFilter() {
        val query = search.text.trim().lowercase()
        val kinds = buildSet {
            if (restBox.isSelected) add(EntryKind.REST)
            if (graphqlBox.isSelected) add(EntryKind.GRAPHQL)
            if (wsBox.isSelected) add(EntryKind.WEBSOCKET)
        }
        val status = statusFilter.selectedItem as? StatusFilter ?: StatusFilter.ALL
        val selected = selectedEntry()
        sorter.rowFilter = object : RowFilter<EntriesTableModel, Int>() {
            override fun include(entry: Entry<out EntriesTableModel, out Int>): Boolean {
                val e = model.entryAt(entry.identifier) ?: return false
                if (e.kind !in kinds && !(e is WsEntry && e.isGraphQL && EntryKind.GRAPHQL in kinds)) return false
                if (!matchesStatus(e, status)) return false
                return query.isEmpty() || matchesQuery(e, query)
            }
        }
        reselect(selected)
    }

    private fun matchesStatus(e: NetworkEntry, f: StatusFilter): Boolean {
        if (f == StatusFilter.ALL) return true
        val code = (e as? HttpEntry)?.response?.code
        val failed = (e as? HttpEntry)?.failure != null || (e as? WsEntry)?.failure != null
        return when (f) {
            StatusFilter.SUCCESS -> code in 200..299
            StatusFilter.REDIRECT -> code in 300..399
            StatusFilter.CLIENT -> code in 400..499
            StatusFilter.SERVER -> code in 500..599
            StatusFilter.ERRORS -> failed || (code != null && code >= 400)
            StatusFilter.PENDING -> (e is HttpEntry && e.isPending) || (e is WsEntry && e.isActive)
            StatusFilter.ALL -> true
        }
    }

    private fun matchesQuery(e: NetworkEntry, q: String): Boolean {
        if (e.url.lowercase().contains(q)) return true
        if (Format.methodLabel(e).lowercase().contains(q) || Format.status(e).first.lowercase().contains(q)) return true
        if (e is HttpEntry) {
            val req = e.request
            if (req?.graphql?.operationName?.lowercase()?.contains(q) == true) return true
            if (q.length >= 3) {
                if (req?.body?.text?.lowercase()?.contains(q) == true) return true
                if (e.response?.body?.text?.lowercase()?.contains(q) == true) return true
            }
        }
        if (e is WsEntry && q.length >= 3) {
            return e.messages.any { it is np.com.manjultamrakar.peephole.protocol.InspectorEvent.WsFrame && (it.payload.text?.lowercase()?.contains(q) == true || it.label?.lowercase()?.contains(q) == true) }
        }
        return false
    }

    private fun updateEmptyText() {
        val snapshot = service.snapshot
        val empty = table.emptyText
        empty.clear()
        when {
            snapshot.error != null -> {
                empty.appendLine(snapshot.error)
                empty.appendLine("Open settings", com.intellij.ui.SimpleTextAttributes.LINK_ATTRIBUTES) { openSettings() }
            }
            snapshot.adb == null -> empty.appendLine("Looking for devices…")
            snapshot.devices.isEmpty() && service.currentKey == null -> empty.appendLine("No devices connected. Start an emulator or plug in a device.")
            service.currentKey == null -> {
                empty.appendLine("No inspectable apps on this device.")
                empty.appendLine("Add the inspector library to your debug build and run the app.")
            }
            model.rowCount > 0 -> empty.appendLine("No calls match the filters")
            else -> empty.appendLine("Waiting for network traffic…")
        }
        table.repaint()
    }

    // ---- actions ----

    private fun createActions() = DefaultActionGroup().apply {
        add(object : DumbAwareToggleAction("Pause Recording", "Stop capturing new calls", AllIcons.Actions.Pause) {
            override fun isSelected(e: AnActionEvent) = !service.store.recording
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                service.store.recording = !state
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Clear", "Clear captured calls for this app", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                shownKey?.let { service.clear(it) }
                details.showEntry(null)
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = model.rowCount > 0
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareToggleAction("Scroll to New Calls", null, AllIcons.RunConfigurations.Scroll_down) {
            override fun isSelected(e: AnActionEvent) = settings.autoScroll
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                settings.autoScroll = state
                if (state) scrollToEnd()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(Separator.getInstance())
        add(copyCurlAction())
        add(object : DumbAwareAction("Export HAR…", "Export the calls shown to an HTTP Archive file", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) = exportHar()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = model.entries.any { it is HttpEntry }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(Separator.getInstance())
        add(object : DumbAwareAction("Refresh Devices", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                service.refreshDevices()
                service.reconnect()
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Settings…", null, AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) = openSettings()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
    }

    private fun createContextMenu() = DefaultActionGroup().apply {
        add(copyCurlAction())
        add(object : DumbAwareAction("Copy URL", null, AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedEntry()?.let { copy(it.url) }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selectedEntry() != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Copy Request", "Copy method, URL, headers and body", null) {
            override fun actionPerformed(e: AnActionEvent) {
                (selectedEntry() as? HttpEntry)?.request?.let { copy(HttpTextExporter.request(it)) }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = (selectedEntry() as? HttpEntry)?.request != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Copy Response", "Copy status line, headers and body", null) {
            override fun actionPerformed(e: AnActionEvent) {
                (selectedEntry() as? HttpEntry)?.response?.let { copy(HttpTextExporter.response(it)) }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = (selectedEntry() as? HttpEntry)?.response != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
        add(object : DumbAwareAction("Copy Response Body") {
            override fun actionPerformed(e: AnActionEvent) {
                (selectedEntry() as? HttpEntry)?.response?.body?.text?.let { copy(it) }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = (selectedEntry() as? HttpEntry)?.response?.body?.text != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        })
    }

    private fun copyCurlAction() = object : DumbAwareAction("Copy as cURL", "Copy the selected request as a cURL command", AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            (selectedEntry() as? HttpEntry)?.request?.let { copy(CurlExporter.toCurl(it)) }
        }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (selectedEntry() as? HttpEntry)?.request != null
        }
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun openSettings() = ShowSettingsUtil.getInstance().showSettingsDialog(project, InspectorConfigurable::class.java)

    private fun exportHar() {
        val visible = (0 until table.rowCount).mapNotNull { model.entryAt(table.convertRowIndexToModel(it)) as? HttpEntry }
        if (visible.isEmpty()) return
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Export HAR", "Save captured calls as HAR", "har"), project)
        val target = dialog.save("network.har") ?: return
        try {
            target.file.writeText(HarExporter.toHar(visible, "0.1.0"))
            PeepholeNotifications.info(project, "Exported ${visible.size} calls to ${target.file.name}")
        } catch (e: Exception) {
            PeepholeNotifications.error(project, "HAR export failed: ${e.message}")
        }
    }

    override fun dispose() {
        details.showEntry(null)
    }
}

internal object PeepholeNotifications {
    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Peephole")
    fun info(project: Project, text: String) = group().createNotification(text, NotificationType.INFORMATION).notify(project)
    fun error(project: Project, text: String) = group().createNotification(text, NotificationType.ERROR).notify(project)
}
