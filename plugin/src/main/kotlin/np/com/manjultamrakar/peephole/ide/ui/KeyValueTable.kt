package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.table.JBTable
import np.com.manjultamrakar.peephole.protocol.Header
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel

/** Two-column, copyable name/value table (headers, overview fields). */
internal class KeyValueTable(keyTitle: String = "Name", valueTitle: String = "Value") {
    private val model = object : AbstractTableModel() {
        var rows: List<Pair<String, String>> = emptyList()
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) keyTitle else valueTitle
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex].let { if (columnIndex == 0) it.first else it.second }
    }
    private val table = JBTable(model).apply {
        setShowGrid(false)
        tableHeader.reorderingAllowed = false
        autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        columnModel.getColumn(0).preferredWidth = 180
        columnModel.getColumn(1).preferredWidth = 520
        columnModel.getColumn(0).cellRenderer = object : ColoredTableCellRenderer() {
            override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
                append(value?.toString() ?: "", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }

    val component: JComponent = JPanel(BorderLayout()).apply { add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER) }

    fun setEmptyText(text: String) {
        table.emptyText.text = text
    }

    fun setRows(rows: List<Pair<String, String>>) {
        model.rows = rows
        model.fireTableDataChanged()
    }

    fun setHeaders(headers: List<Header>?) = setRows(headers.orEmpty().map { it.name to it.value })
}
