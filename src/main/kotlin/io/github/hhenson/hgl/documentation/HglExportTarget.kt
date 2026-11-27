package io.github.hhenson.hgl.documentation

import com.intellij.model.Pointer
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.SmartPointerManager
import io.github.hhenson.hgl.psi.*
import io.github.hhenson.hgl.reference.HglModuleIndex
import io.github.hhenson.hgl.reference.HglQualifiedNameReference
import io.github.hhenson.hgl.reference.HglScope
import io.github.hhenson.hgl.reference.capabilityType
import io.github.hhenson.hgl.reference.declaredValueType

/** Consult the export only for modules whose HGL source is unavailable in the project. */
private fun exportedDeclarations(file: HglFile, module: String, name: String): List<ExportedDoc> {
    if (module == file.modulePath) return emptyList()
    val matches = HglDocModel.forFile(file).declarations("$module::$name")
        .filter { it.exported }
    if (matches.isEmpty() || HglModuleIndex.findFiles(file.project, module).isNotEmpty()) return emptyList()
    return matches
}

internal fun exportTargets(file: HglFile, module: String, name: String, argument: String? = null): List<DocumentationTarget> =
    exportedDeclarations(file, module, name).mapNotNull { record ->
        when {
            argument == null || argument in record.arguments -> HglExportTarget(file, record, argument)
            record.kind == "struct" -> record.members.singleOrNull {
                it.kind == "field" && it.canonicalName == "${record.canonicalName}.$argument"
            }?.let { HglExportTarget(file, it) }
            else -> null
        }
    }

private fun HglQualifiedName.exportModules(file: HglFile): List<String> {
    val ids = identList
    return when (ids.size) {
        1 -> file.useDecls.filter { use -> use.importSet?.importItemList.orEmpty().any { it.name == ids[0].text } }
            .mapNotNull { it.modulePath?.dottedName }.ifEmpty { listOf("core") }
        2 -> listOfNotNull((HglScope.findAlias(this, ids[0].text)?.parent as? HglUseDecl)?.modulePath?.dottedName)
        else -> emptyList()
    }.distinct()
}

internal fun exportTargets(name: HglQualifiedName, argument: String? = null): List<DocumentationTarget> {
    val file = name.containingFile as? HglFile ?: return emptyList()
    return name.exportModules(file).flatMap { exportTargets(file, it, name.identList.last().text, argument) }
}

internal fun exportedFieldTargets(field: HglFieldExpr): List<DocumentationTarget> {
    val name = (field.expr as? HglReferenceExpr)?.qualifiedName ?: return emptyList()
    val member = field.memberName?.text ?: return emptyList()
    val file = field.containingFile as? HglFile ?: return emptyList()
    fun targets(module: String, owner: String, kind: String) =
        exportedDeclarations(file, module, "$owner.$member").filter { it.kind == kind }.map { HglExportTarget(file, it) }

    val bindings = (name.reference as? HglQualifiedNameReference)?.declarations(includeImplementations = false).orEmpty()
    if (bindings.all { it is HglImportItem }) {
        return name.exportModules(file).flatMap { targets(it, name.identList.last().text, "enumMember") }
    }
    val binding = bindings.singleOrNull() ?: return emptyList()
    if (binding is HglInjectItem) {
        val owner = binding.capabilityType ?: return emptyList()
        return targets("core", owner, "nativeProperty")
    }
    val type = binding.declaredValueType() ?: return emptyList()
    val typeReference = type.reference as? HglQualifiedNameReference ?: return emptyList()
    if (typeReference.declarations(includeImplementations = false).any { it !is HglImportItem }) return emptyList()
    val declaringFile = type.containingFile as? HglFile ?: return emptyList()
    return type.exportModules(declaringFile).flatMap { targets(it, type.identList.last().text, "nativeProperty") }
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
        if ("::" in name || name.substringBefore('.') in record.parameterNames) return null
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
