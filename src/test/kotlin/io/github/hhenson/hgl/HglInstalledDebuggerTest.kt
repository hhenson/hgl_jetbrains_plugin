package io.github.hhenson.hgl

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.PathUtil
import com.intellij.util.io.Decompressor
import junit.framework.TestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HglInstalledDebuggerTest : TestCase() {
    fun testPackagedDebuggerLoadsWithTheProductClassLoaders() {
        val home = Path.of(PathManager.getHomePath())
        val productInfo = home.resolve(if (SystemInfo.isMac) "Resources/product-info.json" else "product-info.json")
        val product = JsonParser.parseString(Files.readString(productInfo)).asJsonObject
        val launch = product.getAsJsonArray("launch").map { it.asJsonObject }
            .single { it["arch"].asString == System.getProperty("os.arch") }
        val bootJars = launch.getAsJsonArray("bootClassPathJarNames").map { home.resolve("lib").resolve(it.asString) }
        val probe = InstalledDebuggerProbe::class.java
        val classPath = (bootJars.map(Path::toString) + PathUtil.getJarPathForClass(probe)).joinToString(File.pathSeparator)
        val options = launch.getAsJsonArray("additionalJvmArguments").map {
            it.asString.replace("\$IDE_HOME", home.toString()).replace("\$APP_PACKAGE", home.parent.toString())
        }
        val dir = Files.createTempDirectory("hgl-installed-debugger-")
        try {
            Decompressor.Zip(Path.of(System.getProperty("hglPluginArchive"))).extract(dir.resolve("plugins"))
            val log = dir.resolve("probe.log")
            val paths = listOf("config", "system", "plugins", "log").map { "-Didea.$it.path=${dir.resolve(it)}" }
            val command = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) +
                options + paths + listOf("-Djava.awt.headless=true", "-Didea.home.path=$home", "-cp", classPath, probe.name)
            val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                assertTrue("Installed debugger probe timed out", process.waitFor(60, TimeUnit.SECONDS))
                assertEquals(Files.readString(log), 0, process.exitValue())
            } finally {
                process.destroyForcibly().waitFor()
            }
        } finally {
            FileUtil.delete(dir.toFile())
        }
    }
}
