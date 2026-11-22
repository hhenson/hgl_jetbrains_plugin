package io.github.hhenson.hgl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import io.github.hhenson.hgl.debug.lldbCommandLine
import io.github.hhenson.hgl.run.appendHomebrewBin
import io.github.hhenson.hgl.run.hglCommandLine
import java.io.File

class HglCommandLineTest : HglEditorFixture() {
    fun testMacDebuggerUsesTheActiveAppleToolchain() {
        val command = lldbCommandLine(isMac = true)
        assertEquals("/usr/bin/xcrun", command.exePath)
        assertEquals(listOf("lldb-dap"), command.parametersList.list)
    }

    fun testDefaultMacSearchPathAndConfiguredExecutable() {
        val override = File("custom compiler", "hgl").absolutePath
        val command = hglCommandLine(" $override ")
        assertEquals(override, command.exePath)
        assertEquals(GeneralCommandLine.ParentEnvironmentType.CONSOLE, command.parentEnvironmentType)
        val inherited = command.parentEnvironment["PATH"]
        val path = command.effectiveEnvironment["PATH"]
        if (SystemInfo.isMac) {
            val dirs = path.orEmpty().split(File.pathSeparatorChar)
            assertContainsElements(dirs, "/opt/homebrew/bin")
            if (!inherited.isNullOrEmpty()) assertTrue(path!!.startsWith(inherited))
        } else {
            assertEquals(inherited, path)
        }
    }

    fun testCompilerLookupAndChildToolsUseTheSameSearchPath() {
        val root = FileUtil.createTempDirectory("hgl lookup", null, true)
        val first = root.resolve("toolchain").apply { mkdir() }
        val brew = root.resolve("brew bin").apply { mkdir() }
        val name = if (SystemInfo.isWindows) "hgl.exe" else "hgl"
        fun executable(dir: File) = dir.resolve(name).apply {
            createNewFile()
            setExecutable(true)
        }
        val packaged = executable(brew)
        val command = GeneralCommandLine(name).withEnvironment("PATH", first.path)
        command.appendHomebrewBin(brew.path)
        command.appendHomebrewBin(brew.path)
        val process = command.toProcessBuilder()
        assertEquals(packaged.path, process.command().first())
        assertEquals(first.path + File.pathSeparator + brew.path, process.environment()["PATH"])

        val preferred = executable(first)
        assertEquals(preferred.path, command.toProcessBuilder().command().first())
        command.withExePath(packaged.path)
        assertEquals(packaged.path, command.toProcessBuilder().command().first())
    }

    fun testEmptyPathContainsOnlyHomebrew() {
        val command = GeneralCommandLine("hgl").withEnvironment("PATH", "")
        command.appendHomebrewBin("/opt/homebrew/bin")
        assertEquals("/opt/homebrew/bin", command.effectiveEnvironment["PATH"])
    }
}
