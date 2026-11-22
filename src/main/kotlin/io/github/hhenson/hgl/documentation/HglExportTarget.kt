package io.github.hhenson.hgl.documentation

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPointerManager
import io.github.hhenson.hgl.psi.*
import io.github.hhenson.hgl.reference.HglModuleIndex
import io.github.hhenson.hgl.reference.HglScope

/** Consult the export only for modules whose HGL source is unavailable in the project. */
internal fun exportTargets(file: HglFile, module: String, name: String, argument: String? = null): List<DocumentationTarget> {
    if (module == file.modulePath) return emptyList()
    val matches = HglDocModel.forFile(file).declarations("$module::$name")
        .filter { it.exported && (argument == null || argument in it.arguments) }
    if (matches.isEmpty() || HglModuleIndex.findFiles(file.project, module).isNotEmpty()) return emptyList()
    return matches.map { HglExportTarget(file, it, argument) }
}

internal fun exportTargets(name: HglQualifiedName, argument: String? = null): List<DocumentationTarget> {
    val file = name.containingFile as? HglFile ?: return emptyList()
    val ids = name.identList
    val modules = when (ids.size) {
        1 -> file.useDecls.filter { use -> use.importSet?.importItemList.orEmpty().any { it.name == ids[0].text } }
            .mapNotNull { it.modulePath?.dottedName }.ifEmpty { listOf("core") }
        2 -> listOfNotNull((HglScope.findAlias(name, ids[0].text)?.parent as? HglUseDecl)?.modulePath?.dottedName)
        else -> return emptyList()
    }
    return modules.distinct().flatMap { exportTargets(file, it, ids.last().text, argument) }
}

private class HglExportTarget(
    private val file: HglFile,
    private val record: ExportedDoc,
    private val argument: String? = null,
) : DocumentationTarget, HglLinkedDoc {
    private val signature get() = record.arguments[argument]?.signature ?: record.signature

    override fun createPointer(): Pointer<out DocumentationTarget> {
        val pointer = SmartPointerManager.createPointer(file)
        val name = record.canonicalName
        val origin = record.source
        val kind = record.kind
        val label = argument
        return Pointer {
            pointer.element?.let { current ->
                HglDocModel.forFile(current).declarations(name)
                    .singleOrNull { it.source == origin && it.kind == kind && (label == null || label in it.arguments) }
                    ?.let { HglExportTarget(current, it, label) }
            }
        }
    }

    override fun computePresentation(): TargetPresentation =
        TargetPresentation.builder(signature).locationText(record.source.file).presentation()

    override fun symbol(name: String): DocumentationTarget? {
        // The model carries the declaring module, but not its imports. Never borrow the viewer's imports.
        if (name.substringBefore('.') in record.parameterNames) return null
        val model = HglDocModel.forFile(file)
        val matches = model.declarations("${record.source.module}::$name").ifEmpty {
            qualifiedDocNames(name).flatMap { model.declarations(it) }.filter { it.exported }
        }
        return matches.singleOrNull()?.let { HglExportTarget(file, it) }
    }

    override fun computeDocumentationHint() = html(true)
    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(html(false))

    private fun html(brief: Boolean): String {
        val sections = if (argument == null) record.sections else
            listOfNotNull(record.arguments[argument]?.description?.takeIf { it.isNotBlank() }?.let { HglDocSection(null, it.lines()) })
        val content = docHtml(signature, sections, brief) { symbol(it) != null }
        if (brief || argument != null || record.candidates.isEmpty()) return content
        return buildString {
            append(content).append("<h4>Implementations</h4><ul>")
            for (candidate in record.candidates) {
                append("<li><code>").append(escapeDoc(candidate.signature)).append("</code> · ")
                append(escapeDoc(candidate.source.module)).append("</li>")
            }
            append("</ul>")
        }
    }
}
