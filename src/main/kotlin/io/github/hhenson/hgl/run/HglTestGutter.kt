package io.github.hhenson.hgl.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglIdent
import io.github.hhenson.hgl.psi.HglTestDecl

class HglTestGutter : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val name = element.parent as? HglIdent ?: return null
        if (name.parent !is HglTestDecl || (element.containingFile as? HglFile)?.modulePath.isNullOrEmpty()) return null
        return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
    }
}

class HglTestProducer : LazyRunConfigurationProducer<HglTestConfiguration>() {
    override fun getConfigurationFactory() = ConfigurationTypeUtil.findConfigurationType(HglTestType::class.java)

    override fun setupConfigurationFromContext(
        configuration: HglTestConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val declaration = context.psiLocation?.parentOfType<HglTestDecl>(withSelf = true) ?: return false
        val selector = declaration.selector() ?: return false
        val sourceFile = declaration.containingFile.virtualFile ?: return false
        configuration.selector = selector
        configuration.sourcePath = sourceFile.path
        configuration.name = selector
        sourceElement.set(declaration)
        return true
    }

    override fun isConfigurationFromContext(configuration: HglTestConfiguration, context: ConfigurationContext): Boolean {
        val declaration = context.psiLocation?.parentOfType<HglTestDecl>(withSelf = true) ?: return false
        return declaration.selector() == configuration.selector &&
            declaration.containingFile.virtualFile?.path == configuration.sourcePath
    }

    private fun HglTestDecl.selector(): String? {
        val file = containingFile as? HglFile ?: return null
        val module = file.modulePath?.takeIf { it.isNotBlank() } ?: return null
        val test = name ?: return null
        return "${module}::${test}"
    }
}
