package io.github.hhenson.hgl

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.util.ui.UIUtil
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.run.HglTestSettings
import io.github.hhenson.hgl.run.HglTestRootsDialog
import io.github.hhenson.hgl.run.HglTestSettingsPage
import io.github.hhenson.hgl.run.buildSettings
import java.io.File
import javax.swing.JButton
import javax.swing.JTextArea
import javax.swing.JTextField

class HglBuildSettingsTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testBuildSettingsEditSharedFileAndKeepWorkspaceOverridePrivate() {
        val source = File(project.basePath!!, HglBuildFile.NAME)
        source.writeText(HglBuildFile.encode(HglBuild()).replace("\"build\": {", "\"metadata\": true,\n  \"build\": {\n    \"extra\": 7,"))
        val vf = VfsUtil.findFileByIoFile(source, true)!!
        val page = HglTestSettingsPage(project)
        val settings = project.service<HglTestSettings>().state
        val previous = settings.compilerOverride
        try {
            val panel = page.createComponent()
            page.reset()
            val fields = UIUtil.findComponentsOfType(panel, JTextField::class.java)
            fields.single { it.text == "hgl" }.text = "shared-hgl"
            fields.single { it.text == "bin" }.text = "artifacts"
            fields.single { it.text == "docs" }.text = "reference"
            val paths = UIUtil.findComponentsOfType(panel, JTextArea::class.java)
            paths.single { it.text == "src" }.text = "core/hgl"
            paths.single { it.text.isEmpty() }.text = "core/tests\nmore cases"
            assertEquals(2, UIUtil.findComponentsOfType(panel, JButton::class.java).count { it.text == "Browse…" })
            page.apply()
            val text = VfsUtil.loadText(vf)
            assertTrue(text, "\"extra\": 7" in text && "\"metadata\": true" in text)
            assertEquals(HglBuild(compiler = "shared-hgl", sourceRoots = listOf("core/hgl"), binDir = "artifacts", docsDir = "reference",
                testRoots = listOf("core/tests", "more cases")), HglBuildFile.decode(text))
            settings.compilerOverride = "local-hgl"
            assertEquals("local-hgl", project.buildSettings().compiler)
            assertFalse(VfsUtil.loadText(vf).contains("local-hgl"))
        } finally {
            settings.compilerOverride = previous
            page.disposeUIResources()
        }
    }

    fun testTestRootsDialogAddsTheSelectionOnceAndRequiresOkToSave() {
        val file = project.service<HglBuildFile>()
        val build = HglBuild(compiler = "project-hgl", sourceRoots = listOf("library"), binDir = "custom bin")
        file.save(build, null)
        val folder = File(project.basePath!!, "cases with spaces").apply { mkdirs() }
        val selected = VfsUtil.findFileByIoFile(folder, true)!!
        fun dialog() = HglTestRootsDialog(project, file.read(), selected)
        val cancelled = dialog()
        try {
            UIUtil.findComponentsOfType(cancelled.createCenterPanel(), JButton::class.java).single { it.text == "Add to sources" }.doClick()
            assertTrue(UIUtil.findComponentsOfType(cancelled.createCenterPanel(), JTextArea::class.java).any { it.text == "library\ncases with spaces" })
            cancelled.doCancelAction()
            assertEquals(build, file.read())
        } finally {
            cancelled.disposeIfNeeded()
        }
        val saved = dialog()
        try {
            val add = UIUtil.findComponentsOfType(saved.createCenterPanel(), JButton::class.java).single { it.text == "Add to tests" }
            repeat(2) { add.doClick() }
            saved.performOKAction()
            assertEquals(build.copy(testRoots = listOf("cases with spaces")), file.read())
        } finally {
            saved.disposeIfNeeded()
        }
    }

    fun testExistingBuildFileKeepsInlineTestsAndRejectsMalformedTestRoots() {
        val json = """{"build":{"compiler":"hgl","sourceRoots":["src"],"buildDir":"build","binDir":"bin","docsDir":"docs"}}"""
        val build = HglBuildFile.decode(json)
        assertEquals(listOf("src"), build.caseRoots)
        assertEquals(listOf("src"), build.programRootsForTests)
        for (bad in listOf("null", "42", "[null]", """[""]""")) {
            assertThrows(IllegalArgumentException::class.java) {
                HglBuildFile.decode(json.replace(""""compiler":""", """"testRoots":$bad,"compiler":"""))
            }
        }
    }

    fun testDocumentationButtonIsInstalledInBuildMenuAndToolbar() {
        val actions = ActionManager.getInstance()
        val button = actions.getAction("HGL.GenerateDocumentation")!!
        for (id in listOf("BuildMenu", "MainToolbarRight")) {
            val group = actions.getAction(id) as DefaultActionGroup
            assertTrue("Missing from $id", button in group.getChildren(null))
        }
    }
}
