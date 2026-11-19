package io.github.hhenson.hgl

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.hhenson.hgl.run.HglTestConfiguration

abstract class HglEditorFixture : BasePlatformTestCase() {
    protected fun runConfigurationAt(leaf: PsiElement): HglTestConfiguration =
        ProgressManager.getInstance().runProcess(
            Computable { ConfigurationContext(leaf).configuration!!.configuration as HglTestConfiguration },
            EmptyProgressIndicator(),
        )

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
