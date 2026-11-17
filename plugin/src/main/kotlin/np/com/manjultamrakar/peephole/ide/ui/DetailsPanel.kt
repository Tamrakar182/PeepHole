package np.com.manjultamrakar.peephole.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import np.com.manjultamrakar.peephole.ide.export.HttpTextExporter
import java.awt.datatransfer.StringSelection
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StatusText
import np.com.manjultamrakar.peephole.ide.model.HttpEntry
import np.com.manjultamrakar.peephole.ide.model.NetworkEntry
import np.com.manjultamrakar.peephole.ide.model.WsEntry
import np.com.manjultamrakar.peephole.protocol.ClientLibrary
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Tabs describing the selected call or WebSocket. Components are created once and reused. */
internal class DetailsPanel(project: Project) : JPanel(CardLayout()) {
    private val tabs = JBTabbedPane()
    private val empty = object : JPanel() {
        private val status = object : StatusText(this) {
            override fun isStatusVisible() = true
        }.apply { text = "Select a call to see its details" }

        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            status.paint(this, g)
        }
    }

    private val overview = KeyValueTable("Field", "Value")
    private val requestHeaders = KeyValueTable().apply { setEmptyText("No headers") }
    private var shownHttp: HttpEntry? = null
    private val requestBody = BodyViewer(project, listOf(copyAllAction("Copy Request", "Copy method, URL, headers and body") {
        it.request?.let(HttpTextExporter::request)
    }))
    private val responseHeaders = KeyValueTable().apply { setEmptyText("No headers") }
    private val responseBody = BodyViewer(project, listOf(copyAllAction("Copy Response", "Copy status line, headers and body") {
        it.response?.let(HttpTextExporter::response)
    }))
    private val gqlQuery = BodyViewer(project)
    private val gqlVariables = BodyViewer(project)
    private val messages = MessagesPanel(project)
    private val wsRequestHeaders = KeyValueTable().apply { setEmptyText("No request headers") }
    private val wsResponseHeaders = KeyValueTable().apply { setEmptyText("No response headers") }

    private val requestTab = split(requestHeaders.component, requestBody, 0.35f)
    private val responseTab = split(responseHeaders.component, responseBody, 0.35f)
    private val graphqlTab = split(gqlQuery, gqlVariables, 0.65f)
    private val handshakeTab = split(wsRequestHeaders.component, wsResponseHeaders.component, 0.5f)

    private var shownId: String? = null
    private var shownVersion = -1
    private var shownKind: String? = null

    init {
        tabs.tabComponentInsets = JBUI.emptyInsets()
        add(empty, "empty")
        add(tabs, "tabs")
        showEntry(null)
    }

    fun showEntry(entry: NetworkEntry?) {
        val layout = layout as CardLayout
        shownHttp = entry as? HttpEntry
        if (entry == null) {
            shownId = null
            layout.show(this, "empty")
            return
        }
        layout.show(this, "tabs")
        if (entry.id == shownId && entry.version == shownVersion) return
        val sameEntry = entry.id == shownId
        shownId = entry.id
        shownVersion = entry.version
        when (entry) {
            is HttpEntry -> showHttp(entry, sameEntry)
            is WsEntry -> showWs(entry)
        }
    }

    private fun showHttp(e: HttpEntry, sameEntry: Boolean) {
        val req = e.request
        val res = e.response
        val gql = req?.graphql
        setTabs(
            "http${gql != null}",
            buildList {
                add("Overview" to overview.component)
                add("Request" to requestTab)
                add("Response" to responseTab)
                if (gql != null) add("GraphQL" to graphqlTab)
            },
        )
        overview.setRows(
            buildList {
                add("URL" to e.url)
                add("Method" to (req?.method ?: ""))
                add("Status" to when {
                    e.failure != null -> "Failed"
                    res == null -> "Pending…"
                    else -> "${res.code} ${res.message}".trim()
                })
                e.failure?.let { add("Error" to it.error) }
                res?.protocol?.let { add("Protocol" to it) }
                add("Client" to clientName(req?.client))
                add("Started" to Format.dateTime(e.startTime))
                e.durationMs?.let { add("Duration" to Format.duration(it)) }
                req?.body?.let { add("Request size" to Format.size(it.size)) }
                res?.body?.let { add("Response size" to Format.size(it.size) + if (it.truncated) " (truncated)" else "") }
                if (gql != null) {
                    add("GraphQL operation" to "${gql.operationType.name.lowercase()} ${gql.operationName ?: "(anonymous)"}")
                    if (gql.persisted) add("Persisted query" to "yes (query document not sent)")
                }
            },
        )
        requestHeaders.setHeaders(req?.headers)
        if (!sameEntry) requestBody.showBody(req?.body, "No request body")
        responseHeaders.setHeaders(res?.headers)
        when {
            e.failure != null -> responseBody.show(e.failure!!.error, "Request failed", null)
            res == null -> responseBody.show("Waiting for response…", null, null)
            else -> responseBody.showBody(res.body, "No response body")
        }
        if (gql != null && !sameEntry) {
            gqlQuery.show(
                gql.query ?: "Persisted query — the client sent only a hash, not the query document.",
                "${gql.operationType.name.lowercase()} ${gql.operationName ?: ""}".trim(),
                null,
                extension = if (gql.query != null) "graphql" else null,
            )
            gqlVariables.show(gql.variables ?: "{}", "Variables", "application/json")
        }
    }

    private fun showWs(e: WsEntry) {
        setTabs("ws", listOf("Messages" to messages, "Overview" to overview.component, "Handshake" to handshakeTab))
        overview.setRows(
            buildList {
                add("URL" to e.url)
                add("Status" to Format.status(e).first)
                e.open?.let { add("Handshake" to "HTTP ${it.code}") }
                e.closed?.let { add("Close" to "${it.code} ${it.reason}".trim()) }
                e.failure?.let { add("Error" to it.error) }
                add("Client" to clientName(e.connect?.client))
                add("Started" to Format.dateTime(e.startTime))
                Format.durationLabel(e).takeIf { it.isNotEmpty() }?.let { add("Duration" to it) }
                add("Messages" to "${e.sentCount} sent · ${e.receivedCount} received")
                if (e.droppedMessages > 0) add("Note" to "Older messages were dropped to save memory")
                if (e.isGraphQL) add("Protocol" to "GraphQL over WebSocket")
            },
        )
        wsRequestHeaders.setHeaders(e.connect?.headers)
        wsResponseHeaders.setHeaders(e.open?.headers)
        messages.show(e)
    }

    private fun setTabs(kind: String, list: List<Pair<String, JComponent>>) {
        if (kind == shownKind) return
        val selectedTitle = tabs.selectedIndex.takeIf { it >= 0 }?.let { tabs.getTitleAt(it) }
        shownKind = kind
        tabs.removeAll()
        list.forEach { (title, c) -> tabs.addTab(title, c) }
        val idx = list.indexOfFirst { it.first == selectedTitle }
        if (idx >= 0) tabs.selectedIndex = idx
    }

    private fun copyAllAction(text: String, description: String, render: (HttpEntry) -> String?) =
        object : DumbAwareAction(text, description, AllIcons.Actions.Copy) {
            init {
                templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
            }
            override fun actionPerformed(e: AnActionEvent) {
                shownHttp?.let(render)?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = shownHttp?.let(render) != null
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun clientName(c: ClientLibrary?) = when (c) {
        ClientLibrary.OKHTTP -> "OkHttp"
        ClientLibrary.KTOR -> "Ktor"
        else -> "Unknown"
    }

    private fun split(top: JComponent, bottom: JComponent, proportion: Float) = JPanel(BorderLayout()).apply {
        add(JBSplitter(true, proportion).apply {
            firstComponent = top
            secondComponent = bottom
        }, BorderLayout.CENTER)
    }
}
