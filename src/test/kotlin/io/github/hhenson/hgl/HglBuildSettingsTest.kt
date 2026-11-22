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
import io.github.hhenson.hgl.run.HglTestSettingsPage
import io.github.hhenson.hgl.run.buildSettings
import java.io.File
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
            page.apply()
            val text = VfsUtil.loadText(vf)
            assertTrue(text, "\"extra\": 7" in text && "\"metadata\": true" in text)
            assertEquals(HglBuild(compiler = "shared-hgl", binDir = "artifacts", docsDir = "reference"), HglBuildFile.decode(text))
            settings.compilerOverride = "local-hgl"
            assertEquals("local-hgl", project.buildSettings().compiler)
            assertFalse(VfsUtil.loadText(vf).contains("local-hgl"))
        } finally {
            settings.compilerOverride = previous
            page.disposeUIResources()
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
