package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import np.com.manjultamrakar.peephole.ide.model.WsEntry
import np.com.manjultamrakar.peephole.protocol.Direction
import np.com.manjultamrakar.peephole.protocol.InspectorEvent
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/** WebSocket message list with a payload viewer. */
internal class MessagesPanel(project: Project) : JPanel(BorderLayout()) {
    private var messages: List<InspectorEvent> = emptyList()
    private var shownEntryId: String? = null

    private val model = object : AbstractTableModel() {
        override fun getRowCount() = messages.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = listOf("", "Time", "Message", "Size")[column]
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = messages[rowIndex]
    }
    private val table = JBTable(model).apply {
        setShowGrid(false)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).apply { preferredWidth = 24; maxWidth = 30 }
        columnModel.getColumn(1).apply { preferredWidth = 95; maxWidth = 120 }
        columnModel.getColumn(2).preferredWidth = 500
        columnModel.getColumn(3).apply { preferredWidth = 70; maxWidth = 100 }
        setDefaultRenderer(Any::class.java, MessageRenderer())
        emptyText.text = "No messages yet"
    }
    private val payload = BodyViewer(project)

    init {
        val splitter = JBSplitter(true, 0.45f)
        splitter.firstComponent = ScrollPaneFactory.createScrollPane(table)
        splitter.secondComponent = payload
        add(splitter, BorderLayout.CENTER)
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) showSelected()
        }
    }

    fun show(entry: WsEntry) {
        val sameEntry = shownEntryId == entry.id
        val atEnd = !sameEntry || table.selectedRow < 0 || table.selectedRow == messages.size - 1
        val oldSize = if (sameEntry) messages.size else 0
        shownEntryId = entry.id
        messages = entry.messages.toList()
        if (sameEntry && messages.size >= oldSize) {
            if (messages.size > oldSize) model.fireTableRowsInserted(oldSize, messages.size - 1)
            if (atEnd && table.selectedRow < 0) table.scrollRectToVisible(table.getCellRect(messages.size - 1, 0, true))
        } else {
            model.fireTableDataChanged()
            if (messages.isNotEmpty()) table.setRowSelectionInterval(0, 0) else payload.show("Select a message", null, null)
        }
    }

    private fun showSelected() {
        val m = messages.getOrNull(table.selectedRow) ?: return
        when (m) {
            is InspectorEvent.WsFrame -> payload.showBody(m.payload)
            is InspectorEvent.WsClosing -> payload.show("Close frame ${if (m.initiatedBy == Direction.SENT) "sent" else "received"}\ncode: ${m.code}\nreason: ${m.reason}", null, null)
            is InspectorEvent.WsClosed -> payload.show("Connection closed\ncode: ${m.code}\nreason: ${m.reason}", null, null)
            is InspectorEvent.WsFailure -> payload.show("Connection failed${m.responseCode?.let { " (HTTP $it)" } ?: ""}\n\n${m.error}", null, null)
            else -> payload.show(m.toString(), null, null)
        }
    }

    private inner class MessageRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            val m = value as? InspectorEvent ?: return
            when (column) {
                0 -> when (m) {
                    is InspectorEvent.WsFrame ->
                        if (m.direction == Direction.SENT) append("↑", Format.attrs(Format.REDIRECT, true)) else append("↓", Format.attrs(Format.SUCCESS, true))
                    is InspectorEvent.WsFailure -> append("✕", Format.attrs(Format.SERVER_ERROR, true))
                    else -> append("●", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                1 -> append(Format.time(m.timestamp), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                2 -> when (m) {
                    is InspectorEvent.WsFrame -> {
                        m.label?.let { append("$it  ", Format.attrs(Format.GRAPHQL, true)) }
                        val preview = m.payload.text?.replace('\n', ' ')?.replace('\u001E', ' ')?.take(300) ?: "binary ${Format.size(m.payload.size)}"
                        append(preview)
                    }
                    is InspectorEvent.WsClosing ->
                        append("Close ${if (m.initiatedBy == Direction.SENT) "sent" else "received"}: ${m.code} ${m.reason}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    is InspectorEvent.WsClosed -> append("Closed: ${m.code} ${m.reason}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    is InspectorEvent.WsFailure -> append("Failed: ${m.error}", Format.attrs(Format.SERVER_ERROR))
                    else -> append(m.javaClass.simpleName)
                }
                3 -> if (m is InspectorEvent.WsFrame) append(Format.size(m.payload.size), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
}
