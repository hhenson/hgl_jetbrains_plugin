package io.github.hhenson.hgl

import com.intellij.util.ui.UIUtil
import io.github.hhenson.hgl.debug.HglGraphView
import javax.swing.JButton
import javax.swing.JLabel

class HglGraphViewTest : HglEditorFixture() {
    private val requests = mutableListOf<Pair<Long, Int>>()
    private val view by lazy { HglGraphView { snapshot, node -> requests.add(snapshot to node) } }

    private fun labels() = UIUtil.findComponentsOfType(view, JLabel::class.java).map { it.text }
    private fun button(text: String) = UIUtil.findComponentsOfType(view, JButton::class.java).single { it.text == text }
    private fun node(snapshot: Long) = HglGraphView.parseReply("""
        {"version":1,"snapshot":$snapshot,"node":3,"label":"watch",
         "inputs":[{"name":"price","value":"7 [modified]","links":[{"node":2,"via":"price","label":"feed","port":"out"}]}],
         "outputs":[{"name":"out","value":"true","links":[{"node":4,"via":"out","label":"record","port":"value"}]}]}
    """)

    fun testShowsValuesAndFollowsBothDirectionsWithinOneSnapshot() {
        view.receive(view.beginRead(), null, node(17))
        for (text in listOf("watch [3]", "price", "7 [modified]", "out", "true")) assertContainsElements(labels(), text)
        button("feed [2].out").doClick()
        button("record [4].value").doClick()
        assertEquals(listOf(17L to 2, 17L to 4), requests)
    }

    fun testInspectorErrorsAndMalformedRepliesReplaceOldRows() {
        view.receive(view.beginRead(), null, node(17))
        view.receive(view.beginRead(), 17, HglGraphView.parseReply("""{"version":1,"error":"No HGL node in this frame."}"""))
        assertContainsElements(labels(), "No HGL node in this frame.")
        for (malformed in listOf("{", """{"version":1,"node":3}""")) {
            view.receive(view.beginRead(), null, HglGraphView.parseReply(malformed))
            assertContainsElements(labels(), "Invalid reply from the hgraph graph inspector.")
            assertFalse("7 [modified]" in labels())
        }
    }

    fun testNavigationCannotSwitchSnapshotsOrOverwriteANewerRequest() {
        val late = view.beginRead()
        view.receive(view.beginRead(), 17, node(18))
        assertContainsElements(labels(), "The selected node does not belong to this stop.")
        view.receive(late, null, node(17))
        assertFalse("watch [3]" in labels())
        assertFalse(view.back.isEnabled)
    }

    fun testNoInspectorMessageSurvivesALateReplyAfterReset() {
        val late = view.beginRead()
        view.reset(supported = false)
        view.receive(late, null, node(17))
        assertContainsElements(labels(), "The selected hgraph SDK has no graph inspector.")
        assertFalse("watch [3]" in labels())
    }
}
