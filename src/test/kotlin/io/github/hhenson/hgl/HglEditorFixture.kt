package io.github.hhenson.hgl

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hhenson.hgl.run.HglRunConfiguration
import io.github.hhenson.hgl.run.HglRunProducer

abstract class HglEditorFixture : BasePlatformTestCase() {
    protected fun runConfigurationAt(leaf: PsiElement): HglRunConfiguration {
        val context = ConfigurationContext(leaf)
        return RunConfigurationProducer.getProducers(project).filterIsInstance<HglRunProducer<*>>()
            .mapNotNull { it.createConfigurationFromContext(context) }.single().configuration as HglRunConfiguration
    }

    override fun tearDown() {
        try {
            // A CLion component turns this on during the first test of the
            // process; the fixture's tear-down otherwise reports the code
            // insight settings as damaged.
            CodeInsightSettings.getInstance().AUTO_POPUP_JAVADOC_INFO = false
        } finally {
            super.tearDown()
        }
    }
}
