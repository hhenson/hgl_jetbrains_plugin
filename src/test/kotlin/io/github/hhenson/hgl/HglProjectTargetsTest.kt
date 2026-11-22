package io.github.hhenson.hgl

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.run.HglProjectTargets
import io.github.hhenson.hgl.run.HglRunTestsAction
import io.github.hhenson.hgl.run.HglScopedTestConfiguration
import io.github.hhenson.hgl.run.HglScopedTestType
import java.nio.file.Files
import java.nio.file.Path

class HglProjectTargetsTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testDiscoveryTracksDeclarationsAndPreservesUserConfigurations() {
        val base = Path.of(project.basePath!!)
        val root = Files.createDirectory(base.resolve("src"))
        val source = Files.writeString(root.resolve("prices.hgl"), "module prices\ntest one { assert true }")
        val shared = project.service<HglBuildFile>()
        shared.save(HglBuild(), null)
        val manager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(HglScopedTestType::class.java)
        val mine = manager.createConfiguration("My tests", type)
        (mine.configuration as HglScopedTestConfiguration).testPath = "src"
        manager.addConfiguration(mine)
        val targets = project.service<HglProjectTargets>()
        targets.start()
        fun named(name: String) = manager.allSettings.find { it.name == name }
        PlatformTestUtil.waitWithEventsDispatching("Module was not discovered", { named("prices — Tests") != null }, 10)
        val previous = named("prices — Tests")!!.configuration as HglScopedTestConfiguration
        val file = VfsUtil.findFileByIoFile(source.toFile(), true)!!
        WriteCommandAction.runWriteCommandAction(project) { VfsUtil.saveText(file, "application demo.feed") }
        // Save then immediately Run, before the asynchronous catalog refresh.
        try {
            previous.commandLine()
            fail("A renamed module must not run its former source")
        } catch (error: RuntimeConfigurationError) {
            assertTrue(error.message.orEmpty().contains("prices"))
        }
        PlatformTestUtil.waitWithEventsDispatching("Renamed declaration still listed", {
            named("demo.feed") != null && named("prices — Tests") == null
        }, 10)
        assertSame(mine, named("My tests"))
        try {
            previous.commandLine()
            fail("A stale module selection must not run the whole project")
        } catch (error: RuntimeConfigurationError) {
            assertTrue(error.message.orEmpty().contains("prices"))
        }

        val external = FileUtil.createTempDirectory("hgl extra roots", null, true).toPath()
        Files.writeString(external.resolve("another.hgl"), "module other")
        val settings = requireNotNull(shared.read())
        shared.save(settings.copy(sourceRoots = listOf(external.toString())), settings)
        PlatformTestUtil.waitWithEventsDispatching("Source-root change did not replace targets", {
            named("other — Tests") != null && named("demo.feed") == null
        }, 10)
        val all = named("All HGL Tests")!!.configuration as HglScopedTestConfiguration
        val args = all.commandLine().parametersList.list
        assertEquals(listOf(external.toString()), args.drop(args.indexOf("--") + 1))
        assertFalse("--test-path" in args)
        val context = HglRunTestsAction().settingsFor(project, VfsUtil.findFileByIoFile(base.toFile(), true)!!)
        assertFalse((context.configuration as HglScopedTestConfiguration).allModules)
        assertSame(mine, named("My tests"))
    }
}
