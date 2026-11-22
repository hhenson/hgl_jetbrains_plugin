package io.github.hhenson.hgl

import com.google.gson.JsonObject
import com.intellij.execution.ExecutionException
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.jetbrains.cidr.cpp.external.system.model.ExternalModule
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildAction
import io.github.hhenson.hgl.project.HglBuildFile
import io.github.hhenson.hgl.project.HglCompilationDatabaseImporter
import java.io.File

class HglBuildActionTest : HglEditorFixture() {
    private val descriptor = LightProjectDescriptor()
    override fun getProjectDescriptor() = descriptor

    fun testBuildIsAvailableInMenuAndToolbar() {
        val actions = ActionManager.getInstance()
        val build = actions.getAction("HGL.Build")!!
        for (id in listOf("BuildMenu", "MainToolbarRight")) {
            assertTrue(build in (actions.getAction(id) as DefaultActionGroup).getChildren(null))
        }
    }

    fun testBuildArgumentsAndUnsuccessfulBuild() {
        val base = File(project.basePath!!)
        val output = base.resolve("native output").apply { mkdirs() }
        val database = output.resolve("compile_commands.json")
        val driver = base.resolve(if (SystemInfo.isWindows) "driver.cmd" else "driver")
        val capture = base.resolve("arguments.txt")
        val script = if (SystemInfo.isWindows) {
            "@echo off\r\n:args\r\nif \"%~1\"==\"\" goto done\r\necho %~1>>arguments.txt\r\nshift\r\ngoto args\r\n:done\r\n"
        } else "#!/bin/sh\nprintf '%s\\n' \"\$@\" > arguments.txt\n"
        driver.writeText(script)
        driver.setExecutable(true)
        val settings = HglBuild(compiler = driver.path, sourceRoots = listOf("src one", "src two"), buildDir = output.name)
        project.service<HglBuildFile>().save(settings, null)
        fun failure(): String = try {
            HglBuildAction.build(project, null, EmptyProgressIndicator())
            error("Build should have failed")
        } catch (e: ExecutionException) { e.message.orEmpty() }
        assertTrue(failure().contains("did not produce"))
        assertEquals(listOf("build", "--target", "cpp", "--compile-commands", "--out", output.path, "--", "src one", "src two"),
            capture.readLines())
        database.writeText("[]")
        val generated = output.resolve("module.cpp").apply { writeText("int initial;") }
        val first = HglBuildAction.build(project, null, EmptyProgressIndicator())
        assertEquals(database.toPath(), first.toNioPath())
        val unit = first.parent.findChild("module.cpp")!!
        assertEquals("int initial;", unit.contentsToByteArray().decodeToString())
        generated.writeText("int updated_module;")
        HglBuildAction.build(project, null, EmptyProgressIndicator())
        assertEquals("int updated_module;", unit.contentsToByteArray().decodeToString())
        val diagnostic = "error: unexpected argument '--compile-commands' found"
        driver.appendText(if (SystemInfo.isWindows) "echo $diagnostic 1>&2\r\nexit /b 2\r\n"
            else "echo \"$diagnostic\" >&2\nexit 2\n")
        val message = failure()
        assertTrue(message, message.contains("The configured compiler must support hgl build --compile-commands"))
        assertTrue(message, message.contains(diagnostic))
    }

    fun testNativeDatabaseIsImportedIntoCurrentProject() {
        val importer = project.getService(HglCompilationDatabaseImporter::class.java)
        if (ApplicationNamesInfo.getInstance().fullProductName.contains("RustRover")) {
            assertNull(importer)
            return
        }
        assertNotNull(importer)
        val base = File(project.basePath!!)
        fun writeDatabase(name: String, directory: String = "native output"): VirtualFile {
            val root = base.resolve(directory).apply { mkdirs() }
            val db = root.resolve("compile_commands.json")
            root.resolve(name).writeText("int answer() { return 42; }\n")
            val entry = JsonObject().apply {
                addProperty("directory", root.path)
                addProperty("command", "c++ -std=c++23 -c $name")
                addProperty("file", name)
            }
            db.writeText("[$entry]")
            return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(db)!!.also { it.refresh(false, false) }
        }
        val file = writeDatabase("unit.cpp")
        // Light projects skip the external project manager's normal startup.
        Registry.get("external.system.auto.import.disabled").setValue(false, testRootDisposable)
        ExternalProjectsManagerImpl.getInstance(project).init()
        importer!!.load(file)
        val id = ProjectSystemId.findById("CompDB")!!
        PlatformTestUtil.waitWithEventsDispatching("CLion did not import unit.cpp", { "unit.cpp" in importedCppFiles(project, file) }, 45)
        importer.load(file)
        importer.load(writeDatabase("updated.cpp"))
        PlatformTestUtil.waitWithEventsDispatching("CLion did not replace unit.cpp with updated.cpp", {
            importedCppFiles(project, file) == setOf("updated.cpp")
        }, 45)
        fun linkedPaths() = ExternalSystemApiUtil.getSettings(project, id).linkedProjectsSettings.map { it.externalProjectPath }
        assertEquals(listOf(file.path), linkedPaths())

        val moved = writeDatabase("moved.cpp", "new workspace")
        importer.load(moved)
        PlatformTestUtil.waitWithEventsDispatching("CLion did not import the new build directory", {
            importedCppFiles(project, moved) == setOf("moved.cpp")
        }, 45)
        assertEquals(listOf(moved.path), linkedPaths())
    }
}

internal fun importedCppFiles(project: Project, database: VirtualFile): Set<String> {
    val id = ProjectSystemId.findById("CompDB") ?: return emptySet()
    val data = ProjectDataManager.getInstance().getExternalProjectData(project, id, database.path)?.externalProjectStructure
        ?: return emptySet()
    return ExternalSystemApiUtil.findAllRecursively(data, ExternalModule.OC_MODULE_KEY)
        .flatMap { it.data.resolveConfigurations }.flatMap { it.fileConfigurations }.map { it.file.name }.toSet()
}
