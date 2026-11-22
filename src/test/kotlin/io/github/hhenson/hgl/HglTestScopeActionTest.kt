package io.github.hhenson.hgl

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.run.HglScopedTestConfiguration
import io.github.hhenson.hgl.run.HglTestScopeAction
import org.jdom.Element
import java.io.File

class HglTestScopeActionTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    private val manager get() = ActionManager.getInstance()
    private fun action(id: String) = manager.getAction(id) as HglTestScopeAction

    fun testProjectAndTabMenusExposeSeparateActionsForAnHglSelection() {
        val file = myFixture.addFileToProject("nested/cases.hgl", "module sample\ntest one { assert true }").virtualFile
        val other = myFixture.addFileToProject("plain/readme.txt", "text").virtualFile
        for ((id, label) in mapOf("HGL.RunTests" to "Run HGL Tests", "HGL.RunIntegrationTests" to "Run HGL Integration Tests")) {
            val action = action(id)
            assertEquals(label, action.templatePresentation.text)
            for (menu in listOf("ProjectViewPopupMenu", "EditorTabPopupMenu")) {
                assertTrue(menu, action in (manager.getAction(menu) as DefaultActionGroup).getChildren(null))
            }
            fun visible(vararg selection: VirtualFile): Boolean {
                val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*selection)).build()
                val event = AnActionEvent.createEvent(action, context, null, "ProjectViewPopup", ActionUiKind.POPUP, null)
                action.update(event)
                return event.presentation.isVisible
            }
            assertTrue(visible(file))
            assertTrue(visible(file.parent))
            assertFalse(visible(other))
            assertFalse(visible(other.parent))
            assertFalse(visible(file, other))
            val tab = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file).add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(other)).build()
            val event = AnActionEvent.createEvent(action, tab, null, ActionPlaces.EDITOR_TAB_POPUP, ActionUiKind.POPUP, null)
            action.update(event)
            assertTrue(event.presentation.isVisible)
        }
    }

    fun testFileFolderAndProjectRememberSeparateKindsAndAllDependencyRoots() {
        val base = File(project.basePath!!)
        fun file(name: String): VirtualFile {
            val file = base.resolve(name).apply { parentFile.mkdirs(); writeText("module example") }
            return VfsUtil.findFileByIoFile(file, true)!!
        }
        val testFile = file("src/cases with spaces/checks.hgl")
        val sibling = file("src/sibling.hgl")
        val dependency = file("lib/helper.hgl")
        project.service<HglBuildFile>().save(HglBuild(sourceRoots = listOf("src", "lib")), null)
        val normal = action("HGL.RunTests")
        val integration = action("HGL.RunIntegrationTests")
        val selected = normal.settingsFor(project, testFile)
        assertSame(selected, normal.settingsFor(project, testFile))
        val otherKind = integration.settingsFor(project, testFile)
        assertNotSame(selected, otherKind)
        assertNotSame(selected, normal.settingsFor(project, sibling))
        val roots = listOf("--keep", "build", "--", "src", "lib")
        val prefix = listOf("test", "--target", "cpp")
        fun arguments(action: HglTestScopeAction, file: VirtualFile) =
            (action.settingsFor(project, file).configuration as HglScopedTestConfiguration).commandLine().parametersList.list
        assertEquals(prefix + listOf("--test-path", "src/cases with spaces/checks.hgl") + roots, arguments(normal, testFile))
        assertEquals(prefix + listOf("--test-path", "src/cases with spaces", "--integration-only") + roots,
            arguments(integration, testFile.parent))
        assertEquals(prefix + listOf("--test-path", "lib") + roots, arguments(normal, dependency.parent))
        assertEquals(prefix + roots, arguments(normal, dependency.parent.parent))
        val example = file("examples/demo.hgl")
        assertEquals(prefix + listOf("--test-path", "examples/demo.hgl") + roots + "examples/demo.hgl",
            arguments(normal, example))

        val configuration = otherKind.configuration as HglScopedTestConfiguration
        val debug = configuration.commandLine(java.nio.file.Path.of("session")).parametersList.list
        assertEquals(prefix + listOf("--test-path", "src/cases with spaces/checks.hgl", "--integration-only",
            "--debug-build", "session", "--", "src", "lib"), debug)
        val serialized = Element("configuration")
        configuration.writeExternal(serialized)
        val restored = configuration.factory!!.createTemplateConfiguration(project) as HglScopedTestConfiguration
        restored.readExternal(serialized)
        assertTrue(restored.integrationOnly)
        assertEquals(configuration.testPath, restored.testPath)
        assertEquals(configuration.commandLine().parametersList.list, restored.commandLine().parametersList.list)
        restored.testPath = "missing.hgl"
        assertThrows(RuntimeConfigurationError::class.java) { restored.commandLine() }
    }
}
