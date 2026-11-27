package io.github.hhenson.hgl.debug

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

internal class HglGraphView(private val navigate: (Long, Int) -> Unit) : JPanel(BorderLayout()) {
    private val title = JLabel()
    val back = JButton("Back")
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val history = ArrayDeque<Int>()
    private var selected: Int? = null
    private var snapshot: Long? = null
    private var generation = 0

    init {
        border = JBUI.Borders.empty(6)
        add(JPanel(FlowLayout(FlowLayout.LEADING)).apply { add(back); add(title) }, BorderLayout.NORTH)
        add(JBScrollPane(JPanel(BorderLayout()).apply { add(rows, BorderLayout.NORTH) }), BorderLayout.CENTER)
        back.addActionListener {
            val previous = history.removeLastOrNull()
            val graph = snapshot
            if (previous != null && graph != null) navigate(graph, previous)
        }
    }

    fun reset(supported: Boolean) {
        generation++
        history.clear()
        selected = null
        snapshot = null
        message(if (supported) "Pause in an HGL node to inspect its graph." else "The selected hgraph SDK has no graph inspector.")
    }

    fun beginRead(): Int {
        message("Reading graph…")
        return ++generation
    }

    fun receive(request: Int, expectedSnapshot: Long?, reply: JsonObject) {
        if (request != generation) return
        try {
            showReply(reply, expectedSnapshot)
        } catch (_: RuntimeException) {
            message(INVALID_REPLY)
        }
    }

    fun message(text: String) {
        title.text = text
        back.isEnabled = history.isNotEmpty()
        rows.removeAll()
        rows.revalidate()
        rows.repaint()
    }

    private fun showReply(reply: JsonObject, expectedSnapshot: Long?) {
        if (reply.has("error")) { message(reply["error"].asString); return }
        if (reply["version"]?.asInt != 1) { message("Unsupported graph inspector version."); return }
        val graph = reply["snapshot"].asLong
        if (expectedSnapshot != null && graph != expectedSnapshot) {
            history.clear()
            message("The selected node does not belong to this stop.")
            return
        }
        selected = reply["node"].asInt
        snapshot = graph
        message("${reply["label"].asString} [$selected]")
        for ((key, heading) in listOf("inputs" to "Inputs · predecessors", "outputs" to "Outputs · successors")) {
            rows.add(TitledSeparator(heading).apply { alignmentX = LEFT_ALIGNMENT })
            val ports = reply[key].asJsonArray
            if (ports.isEmpty) rows.add(row().apply { add(JLabel(if (key == "inputs") "No time-series inputs" else "No output")) })
            for (value in ports) {
                val port = value.asJsonObject
                val name = port["name"].asString
                rows.add(row().apply { add(JLabel(name)); add(JLabel(port["value"].asString)) })
                for (entry in port["links"].asJsonArray) {
                    val edge = entry.asJsonObject
                    val target = edge["node"].asInt
                    val via = edge["via"].asString
                    val prefix = if (via != name) "$via  ·  " else ""
                    val label = "$prefix${edge["label"].asString} [$target].${edge["port"].asString}"
                    val link = JButton(label)
                    link.addActionListener {
                        selected?.let { history.addLast(it) }
                        navigate(graph, target)
                    }
                    rows.add(row().apply { add(link) })
                }
            }
        }
        rows.revalidate()
        rows.repaint()
    }

    private fun row() = JPanel(FlowLayout(FlowLayout.LEADING)).apply { alignmentX = LEFT_ALIGNMENT }

    companion object {
        private const val INVALID_REPLY = "Invalid reply from the hgraph graph inspector."

        fun parseReply(text: String): JsonObject = try {
            JsonParser.parseString(text.trim()).asJsonObject
        } catch (_: RuntimeException) {
            errorReply(INVALID_REPLY)
        }

        fun errorReply(message: String) = JsonObject().apply { addProperty("error", message) }
    }
}
