package io.github.hhenson.hgl.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.HglApplicationDecl
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglIdent
import io.github.hhenson.hgl.psi.HglTestDecl
import io.github.hhenson.hgl.psi.HglTypes
import io.github.hhenson.hgl.psi.dottedName

class HglRunGutter : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        val parent = element.parent
        if (parent is HglApplicationDecl && element.node.elementType == HglTypes.APPLICATION_KW &&
            parent.applicationPath() != null) {
            return withExecutorActions(AllIcons.Actions.Execute)
        }
        if (parent is HglIdent && parent.parent is HglTestDecl &&
            !(element.containingFile as? HglFile)?.modulePath.isNullOrEmpty()) {
            return withExecutorActions(AllIcons.RunConfigurations.TestState.Run)
        }
        return null
    }
}

abstract class HglRunProducer<D : PsiElement>(private val declarationClass: Class<D>, private val type: Class<out HglRunType>) :
    LazyRunConfigurationProducer<HglRunConfiguration>() {
    override fun getConfigurationFactory() = ConfigurationTypeUtil.findConfigurationType(type)

    protected abstract fun selector(declaration: D): String?

    private fun declaration(context: ConfigurationContext): D? =
        PsiTreeUtil.getParentOfType(context.psiLocation, declarationClass, false)

    override fun setupConfigurationFromContext(
        configuration: HglRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>,
    ): Boolean {
        val declaration = declaration(context) ?: return false
        val selector = selector(declaration) ?: return false
        val sourceFile = declaration.containingFile.virtualFile ?: return false
        configuration.selector = selector
        configuration.sourcePath = sourceFile.path
        configuration.name = selector
        sourceElement.set(declaration)
        return true
    }

    override fun isConfigurationFromContext(configuration: HglRunConfiguration, context: ConfigurationContext): Boolean {
        val declaration = declaration(context) ?: return false
        return selector(declaration) == configuration.selector &&
            declaration.containingFile.virtualFile?.path == configuration.sourcePath
    }
}

class HglTestProducer : HglRunProducer<HglTestDecl>(HglTestDecl::class.java, HglTestType::class.java) {
    override fun selector(declaration: HglTestDecl): String? {
        val file = declaration.containingFile as? HglFile ?: return null
        val module = file.modulePath?.takeIf { it.isNotBlank() } ?: return null
        val test = declaration.name ?: return null
        return "${module}::${test}"
    }
}

class HglApplicationProducer :
    HglRunProducer<HglApplicationDecl>(HglApplicationDecl::class.java, HglApplicationType::class.java) {
    override fun selector(declaration: HglApplicationDecl) = declaration.applicationPath()
}

// A dangling dot is recovered outside the declaration. The parsed prefix could name another application.
private fun HglApplicationDecl.applicationPath(): String? {
    val path = modulePath ?: return null
    var next = PsiTreeUtil.nextCodeLeaf(path)
    while (next?.node?.elementType == HglTypes.NEWLINE) next = PsiTreeUtil.nextCodeLeaf(next)
    if (next?.node?.elementType == HglTypes.DOT) return null
    return path.dottedName
}
