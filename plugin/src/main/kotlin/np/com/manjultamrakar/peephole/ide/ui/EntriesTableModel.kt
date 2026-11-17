package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import np.com.manjultamrakar.peephole.ide.model.NetworkEntry
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

internal enum class Column(val title: String, val width: Int, val type: Class<*>) {
    TIME("Time", 95, Long::class.javaObjectType),
    TYPE("Type", 80, String::class.java),
    METHOD("Method", 80, String::class.java),
    STATUS("Status", 80, Int::class.javaObjectType),
    HOST("Host", 170, String::class.java),
    PATH("Path / Operation", 380, String::class.java),
    DURATION("Duration", 80, Long::class.javaObjectType),
    SIZE("Size", 80, Long::class.javaObjectType),
}

/** Table model over a live session entry list (owned by the store, EDT-only). */
internal class EntriesTableModel : AbstractTableModel() {
    var entries: List<NetworkEntry> = emptyList()
        private set

    fun setEntries(list: List<NetworkEntry>) {
        entries = list
        fireTableDataChanged()
    }

    fun entryAt(modelRow: Int): NetworkEntry? = entries.getOrNull(modelRow)

    override fun getRowCount() = entries.size
    override fun getColumnCount() = Column.entries.size
    override fun getColumnName(column: Int) = Column.entries[column].title
    override fun getColumnClass(columnIndex: Int): Class<*> = Column.entries[columnIndex].type

    /** Values are sort keys; [EntryCellRenderer] does the display formatting. */
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        val e = entries.getOrNull(rowIndex) ?: return null
        return when (Column.entries[columnIndex]) {
            Column.TIME -> e.startTime
            Column.TYPE -> Format.typeLabel(e)
            Column.METHOD -> Format.methodLabel(e)
            Column.STATUS -> Format.statusSortKey(e)
            Column.HOST -> e.host
            Column.PATH -> Format.pathLabel(e)
            Column.DURATION -> Format.durationSortKey(e)
            Column.SIZE -> Format.sizeSortKey(e)
        }
    }
}

internal class EntryCellRenderer(private val model: EntriesTableModel) : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        val e = model.entryAt(table.convertRowIndexToModel(row)) ?: return
        val col = Column.entries[table.convertColumnIndexToModel(column)]
        when (col) {
            Column.TIME -> append(Format.time(e.startTime), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            Column.TYPE -> append(Format.typeLabel(e), Format.attrs(Format.typeColor(e)))
            Column.METHOD -> append(Format.methodLabel(e), Format.attrs(null, bold = true))
            Column.STATUS -> Format.status(e).let { (text, color) -> append(text, Format.attrs(color, bold = color != null)) }
            Column.HOST -> append(e.host)
            Column.PATH -> {
                val op = (e as? np.com.manjultamrakar.peephole.ide.model.HttpEntry)?.request?.graphql?.operationName
                if (op != null) {
                    append(op, Format.attrs(Format.GRAPHQL, bold = true))
                    append("  " + e.path, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else {
                    append(e.path)
                }
            }
            Column.DURATION -> append(Format.durationLabel(e))
            Column.SIZE -> append(Format.sizeLabel(e))
        }
    }
}
