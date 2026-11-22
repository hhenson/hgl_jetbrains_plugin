package io.github.hhenson.hgl

import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.project.HglProjectGenerator
import io.github.hhenson.hgl.run.HglTestSettings
import java.nio.file.Files

internal fun hglStarter() = AbstractNewProjectStep.EP_NAME.extensionList.filterIsInstance<HglProjectGenerator>().single()

class HglProjectGeneratorTest : HglEditorFixture() {
    fun testProductNewProjectChooserOffersHgl() {
        val starter = hglStarter()
        fun offersStarter(action: AnAction): Boolean = when (action) {
            is ProjectSettingsStepBase<*> -> action.projectGenerator === starter
            is DefaultActionGroup -> action.getChildren(null).any(::offersStarter)
            else -> false
        }
        val manager = ActionManager.getInstance()
        val wizards = listOf("CreateCLionProject", "WelcomeScreen.CreateDirectoryProject")
            .mapNotNull(manager::getAction).filterIsInstance<AbstractNewProjectStep<*>>()
        assertTrue("HGL missing from the New Project chooser", wizards.any(::offersStarter))
    }

    fun testCreatesStarterAndOpensApplication() {
        val root = FileUtil.createTempDirectory("new project", null, true)
        Files.createDirectory(root.toPath().resolve("src"))
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options("custom-hgl", checkOnSave = false))
            val starter = hglStarter()
            assertEquals("HGL", starter.name)
            assertTrue(starter.validate(root.path).isOk)
            val dir = VfsUtil.findFileByIoFile(root, true)!!
            starter.generateProject(project, dir, starter.createPeer().settings, module)
            assertEquals(HglBuild(), HglBuildFile.decode(root.resolve("hgl.json").readText()))
            assertEquals(listOf("/build/", "/bin/", "/docs/", "/.idea/workspace.xml"), root.resolve(".gitignore").readLines())
            assertEquals("custom-hgl", settings.state.executable)
            assertFalse(settings.state.checkOnSave)
            assertNotNull(dir.findFileByRelativePath("src/hello.hgl"))
            assertEmpty(dir.findChild("build")!!.children.toList())
            assertEmpty(dir.findChild("bin")!!.children.toList())
            assertEmpty(dir.findChild("docs")!!.children.toList())
            assertNull(dir.findChild("tests"))
            val main = dir.findFileByRelativePath("src/main.hgl")!!
            assertEquals(main, FileEditorManager.getInstance(project).selectedFiles.single())
            FileEditorManager.getInstance(project).closeFile(main)
        } finally {
            settings.loadState(before)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }

    fun testCollisionAfterValidationLeavesExistingFilesAndSettingsAlone() {
        val root = FileUtil.createTempDirectory("existing project", null, true)
        val starter = hglStarter()
        assertTrue(starter.validate(root.path).isOk)
        val existing = root.resolve("src/hello.hgl")
        existing.parentFile.mkdirs()
        existing.writeText("user content")
        assertFalse(starter.validate(root.path).isOk)
        val roots = project.service<HglTestSettings>().state.roots
        var message = ""
        TestDialogManager.setTestDialog({ message = it; 0 }, testRootDisposable)
        starter.generateProject(project, VfsUtil.findFileByIoFile(root, true)!!, starter.createPeer().settings, module)
        assertTrue(message, "src/hello.hgl" in message)
        assertEquals("user content", existing.readText())
        assertFalse(root.resolve("build").exists())
        assertFalse(root.resolve("bin").exists())
        assertEquals(roots, project.service<HglTestSettings>().state.roots)
    }
}
