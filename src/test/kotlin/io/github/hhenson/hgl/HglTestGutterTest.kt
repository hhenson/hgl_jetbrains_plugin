package io.github.hhenson.hgl

import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import io.github.hhenson.hgl.run.HglTestConfiguration
import io.github.hhenson.hgl.run.HglTestGutter
import io.github.hhenson.hgl.run.HglTestSettings
import org.jdom.Element

class HglTestGutterTest : HglEditorFixture() {
    private fun atCaret(source: String): PsiElement {
        myFixture.configureByText("checks.hgl", source)
        return myFixture.file.findElementAt(myFixture.caretOffset)!!
    }

    private val marker get() = RunLineMarkerContributor.EXTENSION.allForLanguage(HglLanguage).filterIsInstance<HglTestGutter>().single()

    fun testOneActionOnEachNamedDeclaration() {
        for (declaration in listOf("test selected {}", "test { test selected {} }", "@timeout(1s)\nintegration_test selected {}")) {
            val leaf = atCaret("module market.quotes part fixtures\n" + declaration.replace("selected", "<caret>selected"))
            assertNotNull(marker.getInfo(leaf))
            assertNull(marker.getInfo(leaf.parent))
            val configuration = runConfigurationAt(leaf)
            assertEquals("market.quotes::selected", configuration.selector)
            assertEquals(listOf("test", "--test", "market.quotes::selected", "--target", "hgraph", "--", configuration.sourcePath),
                configuration.commandLine().parametersList.list)
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
            val restored = configuration.factory!!.createTemplateConfiguration(project) as HglTestConfiguration
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
