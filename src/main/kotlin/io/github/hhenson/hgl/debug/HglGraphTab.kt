package io.github.hhenson.hgl.debug

import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.platform.dap.DapEvaluationContext
import com.intellij.platform.dap.DapEvaluationResult
import com.intellij.platform.dap.xdebugger.DefaultDapXStackFrame
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import kotlinx.coroutines.CancellationException
import java.awt.event.HierarchyEvent

/** A session-local view of the SDK inspector. Only the graph and selected
 * node numbers cross the adapter boundary. The SDK owns all native layouts. */
internal class HglGraphTab(private val session: XDebugSession, private val supported: Boolean) : Disposable {
    private val view = HglGraphView(::load)
    private var active = false
    private var disposed = false

    fun attach(ui: RunnerLayoutUi) {
        val content = ui.createContent(ID, view, "Graph", null, view.back)
        content.setDisposer(this)
        ui.addContent(content, 0, PlaceInGrid.center, false)
        view.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && active != view.isShowing) {
                active = view.isShowing
                if (!disposed) reset()
            }
        }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = refreshLater()
            override fun stackFrameChanged() = refreshLater()
            override fun sessionResumed() = refreshLater()
            override fun sessionStopped() = refreshLater()
        }, this)
        active = view.isShowing
        reset()
    }

    private fun refreshLater() {
        ApplicationManager.getApplication().invokeLater { if (!disposed) reset() }
    }

    private fun reset() {
        view.reset(supported)
        if (supported && active && session.isSuspended) load(null, null)
    }

    private fun load(snapshot: Long?, node: Int?) {
        val frame = session.currentStackFrame as? DefaultDapXStackFrame
        if (frame == null) {
            view.message("No HGL node in this frame.")
            return
        }
        val stop = session.suspendContext ?: return
        val request = view.beginRead()
        val command = if (node == null) "`hgraph-graph" else "`hgraph-graph $snapshot $node"
        frame.executor.post {
            val reply = try {
                when (val result = with(frame.frame) { evaluate(command, DapEvaluationContext.Repl) }) {
                    is DapEvaluationResult.Success -> HglGraphView.parseReply(result.variable.value)
                    is DapEvaluationResult.Error -> HglGraphView.errorReply(result.errorMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HglGraphView.errorReply(e.localizedMessage ?: "Could not read graph.")
            }
            ApplicationManager.getApplication().invokeLater {
                if (!disposed && active && session.suspendContext === stop) view.receive(request, snapshot, reply)
            }
        }
    }

    override fun dispose() { disposed = true }

    companion object {
        const val ID = "HglGraph"
    }
}
