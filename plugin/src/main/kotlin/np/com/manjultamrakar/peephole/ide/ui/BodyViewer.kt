package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import np.com.manjultamrakar.peephole.ide.settings.InspectorSettings
import np.com.manjultamrakar.peephole.protocol.Base64Codec
import np.com.manjultamrakar.peephole.protocol.Body
import np.com.manjultamrakar.peephole.protocol.Json
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel

/** Read-only, syntax-highlighted viewer for bodies and payloads. */
internal class BodyViewer(
    private val project: Project,
    /** Extra toolbar actions shown after the built-in ones (e.g. copying the whole message). */
    extraActions: List<com.intellij.openapi.actionSystem.AnAction> = emptyList(),
) : JPanel(BorderLayout()) {
    private val info = JBLabel().apply {
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        border = JBUI.Borders.empty(3, 6)
    }
    private val editor = EditorTextField(EditorFactory.getInstance().createDocument(""), project, PlainTextFileType.INSTANCE, true, false).apply {
        addSettingsProvider { e ->
            e.setHorizontalScrollbarVisible(true)
            e.setVerticalScrollbarVisible(true)
            e.settings.isLineNumbersShown = true
            e.settings.isFoldingOutlineShown = true
            e.settings.isUseSoftWraps = true
            e.settings.additionalLinesCount = 0
            e.setBorder(JBUI.Borders.empty())
            e.foldingModel.isFoldingEnabled = true
            applyFolds(e)
        }
    }

    /** Text currently displayed (after formatting), used by Copy. */
    private var shownText = ""
    /** Foldable ranges of [shownText]: start offset, end offset (exclusive), placeholder. */
    private var folds: List<Triple<Int, Int, String>> = emptyList()

    init {
        val actions = DefaultActionGroup(
            object : DumbAwareAction("Expand All", "Expand all blocks", AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) = setExpanded(true)
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = folds.isNotEmpty() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Collapse All", "Collapse all nested blocks", AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) = setExpanded(false)
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = folds.isNotEmpty() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            },
            object : DumbAwareAction("Copy Body", "Copy the body as shown", AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(shownText))
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = shownText.isNotEmpty() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            },
        )
        if (extraActions.isNotEmpty()) {
            actions.addSeparator()
            extraActions.forEach { actions.add(it) }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("PeepholeBody", actions, true).apply {
            targetComponent = this@BodyViewer
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }
        val header = JPanel(BorderLayout()).apply {
            add(info, BorderLayout.CENTER)
            add(toolbar.component, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(editor, BorderLayout.CENTER)
    }

    fun showBody(body: Body?, emptyText: String = "No body") {
        if (body == null) {
            show(emptyText, null, null)
            return
        }
        val meta = listOfNotNull(
            body.contentType,
            if (body.size >= 0) Format.size(body.size) else null,
            if (body.truncated) "truncated" else null,
        ).joinToString(" · ")
        when {
            body.omittedReason != null -> show(body.omittedReason!!, meta, null)
            body.text != null -> show(if (body.text!!.isEmpty()) "(empty)" else body.text!!, meta, body.contentType)
            body.base64 != null -> show(hexDump(Base64Codec.decode(body.base64!!)), "$meta · binary (hex)", null)
            else -> show(emptyText, meta, null)
        }
    }

    /** Shows raw text; [contentType] or [extension] pick the highlighter. */
    fun show(text: String, meta: String?, contentType: String?, extension: String? = null) {
        var source = text
        var ext = extension ?: extensionFor(contentType, text)
        var note: String? = null
        if (extension == null) {
            recordSeparated(text)?.let { records ->
                ext = "json"
                if (records.size == 1) {
                    source = records[0]
                } else {
                    source = records.joinToString(",", "[", "]")
                    note = "${records.size} records (␞-separated), shown as an array"
                }
            }
        }
        info.text = listOfNotNull(meta?.takeIf { it.isNotEmpty() }, note).joinToString(" · ")
        val pretty = when (ext) {
            "json" -> if (InspectorSettings.getInstance().state.prettyPrintJson) Json.prettyOrNull(source) ?: source else source
            "graphql" -> GraphQLFormatter.formatOrNull(source) ?: source
            else -> source
        }
        shownText = StringUtil.convertLineSeparators(pretty)
        folds = if (ext == "json" || ext == "graphql") foldRanges(shownText, graphql = ext == "graphql") else emptyList()
        val document = EditorFactory.getInstance().createDocument(shownText)
        editor.setNewDocumentAndFileType(fileType(ext), document)
        editor.setCaretPosition(0)
        editor.editor?.let { applyFolds(it) }
    }

    private fun applyFolds(e: Editor) {
        if (folds.isEmpty() || e.document.textLength != shownText.length) return
        val model = e.foldingModel
        model.runBatchFoldingOperation {
            for ((start, end, placeholder) in folds) {
                if (model.getFoldRegion(start, end) == null) model.addFoldRegion(start, end, placeholder)
            }
        }
    }

    /** Expands everything, or collapses every block except the outermost ones. */
    private fun setExpanded(expanded: Boolean) {
        val e = editor.editor as? EditorEx ?: return
        applyFolds(e)
        val regions = e.foldingModel.allFoldRegions
        e.foldingModel.runBatchFoldingOperation {
            for (r in regions) {
                val outermost = regions.none { o -> o !== r && o.startOffset <= r.startOffset && o.endOffset >= r.endOffset }
                r.isExpanded = expanded || outermost
            }
        }
    }

    /**
     * Payloads framed with the ASCII record separator (0x1E), as used by SignalR, where every
     * record is JSON. Returns the records, or null when the text isn't framed that way.
     */
    private fun recordSeparated(text: String): List<String>? {
        if ('\u001E' !in text) return null
        val records = text.split('\u001E').map { it.trim() }.filter { it.isNotEmpty() }
        if (records.isEmpty() || records.any { Json.parseOrNull(it) == null }) return null
        return records
    }

    /** Multi-line `{}` / `[]` blocks, skipping brackets inside strings (and GraphQL comments). */
    private fun foldRanges(text: String, graphql: Boolean): List<Triple<Int, Int, String>> {
        if (text.length > 4_000_000) return emptyList()
        val out = ArrayList<Triple<Int, Int, String>>()
        val stack = ArrayDeque<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                graphql && c == '#' -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                graphql && text.startsWith("\"\"\"", i) -> i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 2 }
                c == '"' -> {
                    i++
                    while (i < text.length && text[i] != '"' && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
                }
                c == '{' || c == '[' -> stack.addLast(i)
                c == '}' || c == ']' -> {
                    val open = stack.removeLastOrNull()
                    if (open != null && (text[open] == '{') == (c == '}') && text.indexOf('\n', open) in open until i) {
                        out += Triple(open, i + 1, if (c == '}') "{…}" else "[…]")
                        if (out.size >= 20_000) return out
                    }
                }
            }
            i++
        }
        return out
    }

    private fun extensionFor(contentType: String?, text: String): String? {
        val ct = contentType?.lowercase() ?: ""
        val trimmed = text.trimStart()
        return when {
            ct.contains("json") -> "json"
            ct.contains("graphql") -> "graphql"
            ct.contains("html") -> "html"
            ct.contains("xml") -> "xml"
            ct.contains("javascript") -> "js"
            ct.contains("css") -> "css"
            (trimmed.startsWith("{") || trimmed.startsWith("[")) && Json.parseOrNull(text.trim()) != null -> "json"
            trimmed.startsWith("<?xml") -> "xml"
            else -> null
        }
    }

    private fun fileType(extension: String?): FileType {
        if (extension == null) return PlainTextFileType.INSTANCE
        val ft = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        return if (ft is UnknownFileType) PlainTextFileType.INSTANCE else ft
    }

    private fun hexDump(bytes: ByteArray, limit: Int = 16 * 1024): String {
        val sb = StringBuilder()
        val n = minOf(bytes.size, limit)
        var i = 0
        while (i < n) {
            sb.append(String.format("%08x  ", i))
            val end = minOf(i + 16, n)
            for (j in i until i + 16) {
                if (j < end) sb.append(String.format("%02x ", bytes[j])) else sb.append("   ")
                if (j == i + 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        if (bytes.size > n) sb.append("… ${bytes.size - n} more bytes\n")
        return sb.toString()
    }
}
