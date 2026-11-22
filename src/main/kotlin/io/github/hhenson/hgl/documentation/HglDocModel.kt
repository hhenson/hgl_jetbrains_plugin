package io.github.hhenson.hgl.documentation

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.run.HglTestSettings
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class DocLocation(val module: String, val part: String?, val file: String, val line: Int, val column: Int)
internal data class DocArgument(val signature: String, val description: String?)
internal data class ExportedDoc(
    val canonicalName: String, val kind: String, val signature: String, val source: DocLocation,
    val exported: Boolean, val sections: List<HglDocSection>, val arguments: Map<String, DocArgument>,
    val members: List<ExportedDoc>, val candidates: List<ExportedDoc>, val parameterNames: Set<String>,
)

internal class HglDocModel(private val byName: Map<String, List<ExportedDoc>>) {
    fun declarations(name: String): List<ExportedDoc> = byName[name].orEmpty()

    companion object {
        private val empty = HglDocModel(emptyMap())
        private val key = Key.create<CachedValue<HglDocModel>>("hgl.documentation.v1")

        fun forFile(file: HglFile): HglDocModel {
            val project = file.project
            val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val setting = project.service<HglTestSettings>().state.documentationModel.trim()
            val json = if (setting.isEmpty()) {
                root?.findChild("documentation.json") ?: file.virtualFile?.let {
                    ProjectFileIndex.getInstance(project).getContentRootForFile(it)?.findChild("documentation.json")
                }
            } else {
                val path = try { Path.of(setting) } catch (_: InvalidPathException) { return empty }
                if (path.isAbsolute) LocalFileSystem.getInstance().findFileByNioFile(path)
                else root?.findFileByRelativePath(FileUtil.toSystemIndependentName(setting))
            } ?: return empty
            val psi = PsiManager.getInstance(project).findFile(json) ?: return empty
            return cached(psi)
        }

        private fun cached(file: PsiFile): HglDocModel =
            CachedValuesManager.getManager(file.project).getCachedValue(file, key, {
                CachedValueProvider.Result.create(read(file.viewProvider.contents.toString()), file)
            }, false)

        internal fun read(text: String): HglDocModel {
            try {
                val root = JsonParser.parseString(text).asJsonObject
                val version = root.get("schemaVersion")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                if (version?.isNumber != true || version.asString != "1") return empty
                val records = root.objects("modules").flatMap { module -> module.objects("declarations").map { record(it) } }
                return HglDocModel(records.flatMap { listOf(it) + it.members }.groupBy { it.canonicalName })
            } catch (_: JsonParseException) {
                return empty
            } catch (_: IllegalStateException) {
                return empty
            } catch (_: NumberFormatException) {
                return empty
            }
        }
    }
}

private fun JsonObject.string(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
private fun JsonObject.required(key: String) = string(key) ?: error("Missing $key")
private fun JsonObject.objects(key: String) = get(key)?.asJsonArray?.map { it.asJsonObject } ?: error("Missing $key")

private fun record(item: JsonObject, includeChildren: Boolean = true): ExportedDoc {
    val name = item.required("name")
    val kind = item.required("kind")
    val source = item.get("source")?.asJsonObject ?: error("Missing source")
    val location = DocLocation(source.required("module"), source.string("part"), source.required("file"),
        source.required("line").toInt(), source.required("column").toInt())
    val doc = item.get("documentation")?.takeUnless { it.isJsonNull }?.asJsonObject
    val sections = mutableListOf<HglDocSection>()
    doc?.string("summary")?.takeIf { it.isNotBlank() }?.let { sections += HglDocSection(null, it.lines()) }
    val descriptions = mutableMapOf<String, String>()
    for (section in doc?.objects("sections").orEmpty()) {
        val heading = section.string("heading")
        val recognized = section.string("recognized") == "true"
        sections += HglDocSection(heading, section.required("text").lines(), recognized)
        if (recognized && heading == "Args:") for (entry in section.objects("entries")) {
            descriptions[entry.required("key")] = entry.required("text")
        }
    }
    val genericParameters = item.objects("genericParameters")
    val generics = genericParameters.map { it.string("written") ?: it.required("name") }
    val options = item.objects("configurationParameters").associate(::parameter)
    val parameters = item.objects("parameters").associate(::parameter)
    val prefix = when (kind) {
        "function", "candidate", "nativeCandidate", "nativeFunction", "nativeImplementation", "nativeCandidateImplementation" -> buildString {
            if (kind.startsWith("native")) append("native ")
            if (kind == "candidate" || kind.startsWith("nativeCandidate")) append("impl ")
            if (item.string("role") == "value") append("const ")
            append("fn ")
        }
        "nativeType" -> "native type "
        "constant" -> "const "
        "field", "nativeProperty", "enumMember" -> ""
        "struct" -> if (item.string("abstractStruct") == "true") "abstract struct " else "struct "
        else -> "$kind "
    }
    val signature = buildString {
        if (item.required("exported") == "true" && kind !in listOf("field", "nativeProperty", "enumMember")) append("export ")
        append(prefix).append(name)
        if (generics.isNotEmpty()) append(generics.joinToString(", ", "<", ">"))
        if (options.isNotEmpty()) append(options.values.joinToString(", ", "{", "}"))
        if (prefix.endsWith("fn ") || kind == "operator") append(parameters.values.joinToString(", ", "(", ")"))
        item.get("result")?.takeUnless { it.isJsonNull }?.asJsonObject?.let {
            append(' ').append(it.string("written") ?: "-> " + it.required("type"))
        }
        item.string("type")?.let { append(": ").append(it) }
        item.string("requires")?.let { append(' ').append(it) }
    }
    return ExportedDoc(item.required("canonicalName"), kind, signature, location, item.required("exported") == "true",
        sections, parameters.mapValues { (key, written) -> DocArgument(written, descriptions[key]) },
        if (includeChildren) item.objects("members").map { record(it, false) } else emptyList(),
        if (includeChildren) item.objects("candidates").map { record(it, false) } else emptyList(),
        parameters.keys + options.keys + genericParameters.map { it.required("name") })
}

private fun parameter(value: JsonObject): Pair<String, String> {
    val name = value.required("name")
    return name to (value.string("written") ?: "$name: " + value.required("type"))
}
