package io.github.hhenson.hgl

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.run.HglApplicationProducer
import io.github.hhenson.hgl.run.HglRunConfiguration
import io.github.hhenson.hgl.run.HglRunGutter
import io.github.hhenson.hgl.run.HglTestSettings
import org.jdom.Element

class HglRunGutterTest : HglEditorFixture() {
    private fun atCaret(source: String): PsiElement {
        myFixture.configureByText("checks.hgl", source)
        return myFixture.file.findElementAt(myFixture.caretOffset)!!
    }

    private val marker get() = RunLineMarkerContributor.EXTENSION.allForLanguage(HglLanguage).filterIsInstance<HglRunGutter>().single()

    fun testOneActionOnEachNamedDeclaration() {
        for (declaration in listOf("test selected {}", "test { test selected {} }", "@timeout(1s)\nintegration_test selected {}")) {
            val leaf = atCaret("module market.quotes part fixtures\n" + declaration.replace("selected", "<caret>selected"))
            assertNotNull(marker.getInfo(leaf))
            assertNull(marker.getInfo(leaf.parent))
            val configuration = runConfigurationAt(leaf)
            assertEquals("HglSingleTest", configuration.type.id)
            assertEquals("market.quotes::selected", configuration.selector)
            assertEquals(listOf("test", "--test", "market.quotes::selected", "--target", "hgraph", "--", configuration.sourcePath),
                configuration.commandLine().parametersList.list)
        }
    }

    fun testApplicationHeaderHasOneActionAndUsesTheDeclaredPath() {
        val leaf = atCaret("<caret>application demo.east\n.backtest\neval(ticks())")
        val marked = PsiTreeUtil.collectElements(myFixture.file) { marker.getInfo(it) != null }
        assertEquals(listOf(leaf), marked.toList())
        val configuration = runConfigurationAt(leaf)
        assertEquals("HglApplication", configuration.type.id)
        assertEquals("demo.east.backtest", configuration.name)
        assertEquals(listOf("run", "--application", "demo.east.backtest", "--target", "hgraph", "--",
            myFixture.file.virtualFile.path), configuration.commandLine().parametersList.list)
    }

    fun testApplicationActionRequiresANamedHeader() {
        val producer = RunConfigurationProducer.getInstance(HglApplicationProducer::class.java)
        for (source in listOf("<caret>application", "<caret>application demo.", "<caret>application demo # path\n.",
                "<caret>module demo",
                "module demo\nfn <caret>application() {}", "application demo\nuse <caret>demo.lib",
                "application demo\n<caret>eval(ticks())")) {
            val leaf = atCaret(source)
            assertNull(source, marker.getInfo(leaf))
            assertNull(source, producer.createConfigurationFromContext(ConfigurationContext(leaf)))
        }
    }

    fun testApplicationRerunUsesSharedSettingsAndMatchesTheSameDeclaration() {
        val leaf = atCaret("application demo.<caret>backtest")
        val configuration = runConfigurationAt(leaf)
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options("tools/my compiler", "app sources\nlib", "my python"))
            val external = Element("configuration")
            configuration.writeExternal(external)
            val restored = configuration.factory!!.createTemplateConfiguration(project) as HglRunConfiguration
            restored.readExternal(external)
            val producer = RunConfigurationProducer.getInstance(HglApplicationProducer::class.java)
            assertTrue(producer.isConfigurationFromContext(restored, ConfigurationContext(leaf)))
            val command = restored.commandLine()
            assertEquals("tools/my compiler", command.exePath)
            assertEquals(listOf("run", "--application", "demo.backtest", "--target", "hgraph", "--python",
                "my python", "--", "app sources", "lib"), command.parametersList.list)
            val other = atCaret("application other.<caret>backtest")
            assertFalse(producer.isConfigurationFromContext(restored, ConfigurationContext(other)))
            restored.selector = "demo::backtest"
            assertThrows(RuntimeConfigurationError::class.java) { restored.commandLine() }
        } finally {
            settings.loadState(before)
        }
    }

    fun testHelpersAndAnonymousContextsHaveNoAction() {
        for (source in listOf("module m\n<caret>test {}", "module m\ntest { fn <caret>helper() {} }",
                "module m\ntest example { assert <caret>true }", "test <caret>missing_module {}")) {
            assertNull(marker.getInfo(atCaret(source)))
        }
    }

    fun testRunAndRerunUseProjectSettingsAndPreserveArguments() {
        val leaf = atCaret("module m\ntest <caret>selected {}")
        val configuration = runConfigurationAt(leaf)
        val settings = project.service<HglTestSettings>()
        val before = settings.state
        try {
            settings.loadState(HglTestSettings.Options("tools/my compiler", "core sources\nchecks", "my python"))
            val external = Element("configuration")
            configuration.writeExternal(external)
            val restored = configuration.factory!!.createTemplateConfiguration(project) as HglRunConfiguration
            restored.readExternal(external)
            val cmd = restored.commandLine()
            assertEquals("tools/my compiler", cmd.exePath)
            assertEquals(listOf("test", "--test", "m::selected", "--target", "hgraph", "--python", "my python",
                "--", "core sources", "checks"), cmd.parametersList.list)
            assertEquals(configuration.sourcePath, restored.sourcePath)
            restored.selector = ""
            assertThrows(RuntimeConfigurationError::class.java) { restored.commandLine() }
        } finally {
            settings.loadState(before)
        }
    }
}
