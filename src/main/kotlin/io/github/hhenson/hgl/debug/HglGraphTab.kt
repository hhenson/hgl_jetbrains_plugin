package io.github.hhenson.hgl.debug

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.platform.dap.DapEvaluationContext
import com.intellij.platform.dap.DapEvaluationResult
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.CancellationException
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/** A session-local view of the SDK inspector. Only the graph and selected
 * node numbers cross the adapter boundary. The SDK owns all native layouts. */
internal class HglGraphTab(private val session: XDebugSession, private val supported: Boolean) :
    JPanel(BorderLayout()), Disposable {
    private val title = JLabel()
    private val back = JButton("Back")
    private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val history = ArrayDeque<Int>()
    private var selected: Int? = null
    private var snapshot: Long? = null
    private var generation = 0
    private var active = false
    private var disposed = false

    init {
        border = JBUI.Borders.empty(6)
        add(JPanel(FlowLayout(FlowLayout.LEADING)).apply { add(back); add(title) }, BorderLayout.NORTH)
        add(JBScrollPane(JPanel(BorderLayout()).apply { add(rows, BorderLayout.NORTH) }), BorderLayout.CENTER)
        back.addActionListener {
            val previous = history.removeLastOrNull()
            if (previous != null) load(previous)
        }
    }

    fun attach(ui: RunnerLayoutUi) {
        val content = ui.createContent(ID, this, "Graph", null, back)
        content.setDisposer(this)
        ui.addContent(content, 0, PlaceInGrid.center, false)
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && active != isShowing) {
                active = isShowing
                if (!disposed) reset()
            }
        }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = refreshLater()
            override fun stackFrameChanged() = refreshLater()
            override fun sessionResumed() = refreshLater()
            override fun sessionStopped() = refreshLater()
        }, this)
        active = isShowing
        reset()
    }

    private fun refreshLater() {
        ApplicationManager.getApplication().invokeLater { if (!disposed) reset() }
    }

    private fun reset() {
        generation++
        history.clear()
        selected = null
        snapshot = null
        if (!supported) message("The selected hgraph SDK has no graph inspector.")
        else if (!session.isSuspended || !active) message("Pause in an HGL node to inspect its graph.")
        else load(null)
    }

    private fun message(text: String) {
        title.text = text
        back.isEnabled = history.isNotEmpty()
        rows.removeAll()
        rows.revalidate()
        rows.repaint()
    }

    private fun load(node: Int?) {
        val frame = session.currentStackFrame as? DefaultDapXStackFrame
        if (frame == null) {
            message("No HGL node in this frame.")
            return
        }
        val stop = session.suspendContext ?: return
        val request = ++generation
        val command = if (node == null) "`hgraph-graph" else "`hgraph-graph $snapshot $node"
        message("Reading graph…")
        frame.executor.post {
            val reply = try {
                when (val result = with(frame.frame) { evaluate(command, DapEvaluationContext.Repl) }) {
                    is DapEvaluationResult.Success -> JsonParser.parseString(result.variable.value.trim()).asJsonObject
                    is DapEvaluationResult.Error -> JsonObject().apply { addProperty("error", result.errorMessage) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JsonObject().apply { addProperty("error", e.localizedMessage ?: "Could not read graph.") }
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && request == generation && active && session.suspendContext === stop) {
                    try {
                        showReply(reply)
                    } catch (_: RuntimeException) {
                        message("Invalid reply from the hgraph graph inspector.")
                    }
                }
            }
        }
    }

    private fun showReply(reply: JsonObject) {
        if (reply.has("error")) { message(reply["error"].asString); return }
        if (reply["version"]?.asInt != 1) { message("Unsupported graph inspector version."); return }
        selected = reply["node"].asInt
        snapshot = reply["snapshot"].asLong
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
                        load(target)
                    }
                    rows.add(row().apply { add(link) })
                }
            }
        }
        rows.revalidate()
        rows.repaint()
    }

    private fun row() = JPanel(FlowLayout(FlowLayout.LEADING)).apply { alignmentX = LEFT_ALIGNMENT }

    override fun dispose() { disposed = true; generation++ }

    companion object {
        const val ID = "HglGraph"
    }
}
