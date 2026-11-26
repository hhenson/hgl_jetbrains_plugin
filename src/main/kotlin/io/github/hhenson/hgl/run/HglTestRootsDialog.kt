package io.github.hhenson.hgl.run

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.diagnostics.HglCheckService
import io.github.hhenson.hgl.project.HglBuild
import io.github.hhenson.hgl.project.HglBuildFile
import java.nio.file.Path
import kotlin.reflect.KMutableProperty0

/** Both editors write the same source and test roots in hgl.json. */
internal class HglRootFields(private val project: Project, private val selected: VirtualFile? = null) {
    private var sources = ""
    private var tests = ""

    fun reset(build: HglBuild) {
        sources = build.sourceRoots.joinToString("\n")
        tests = build.testRoots.joinToString("\n")
    }

    fun applyTo(build: HglBuild) = build.copy(sourceRoots = paths(sources), testRoots = paths(tests))

    fun rows(panel: Panel) = with(panel) {
        if (selected != null) row("Selection:") { label(relative(selected)) }
        row("Source roots:") {
            pathsField(::sources, "Choose HGL Source Roots", "Add to sources", "Files and folders to compile, including libraries your tests import.")
        }
        row("Test roots:") {
            pathsField(::tests, "Choose HGL Test Roots", "Add to tests", "Where to find test cases. Leave empty for tests written alongside source code.")
        }
    }

    private fun relative(file: VirtualFile): String {
        val base = project.basePath?.let(Path::of) ?: return file.path
        val path = file.toNioPath()
        return if (path.startsWith(base)) base.relativize(path).joinToString("/").ifEmpty { "." } else file.path
    }

    private fun paths(text: String) = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private fun Row.pathsField(value: KMutableProperty0<String>, title: String, addLabel: String, hint: String) {
        val field = textArea().bindText(value).align(AlignX.FILL).resizableColumn().comment(hint).component
        field.rows = 3
        field.columns = 28
        val base = project.basePath?.let(Path::of) ?: return
        fun add(files: List<VirtualFile>) {
            field.text = (paths(field.text) + files.map(::relative)).distinct().joinToString("\n")
        }
        button("Browse…") {
            val descriptor = FileChooserDescriptor(true, true, false, false, false, true)
                .withTitle(title).withFileFilter { it.fileType == HglFileType }
            FileChooser.chooseFiles(descriptor, project, selected ?: LocalFileSystem.getInstance().findFileByNioFile(base), ::add)
        }
        if (selected != null) button(addLabel) { add(listOf(selected)) }
    }
}

internal class HglTestRootsDialog(
    private val project: Project, private val previous: HglBuild?, selected: VirtualFile?,
) : DialogWrapper(project) {
    private val original = previous ?: project.service<HglTestSettings>().state.sharedDefaults()
    private val roots = HglRootFields(project, selected).apply { reset(original) }
    private val editor = panel {
        roots.rows(this)
        row { comment("These paths are shared by the project in hgl.json. Enter one path per line.") }
    }
    init {
        editor.reset()
        title = "Configure HGL Test Roots"
        init()
    }

    public override fun createCenterPanel() = editor

    override fun doOKAction() {
        editor.apply()
        val updated = roots.applyTo(original)
        if (updated != original) {
            try {
                project.service<HglBuildFile>().save(updated, previous)
            } catch (error: Exception) {
                setErrorText(error.localizedMessage ?: "Cannot save hgl.json.")
                return
            }
            project.getServiceIfCreated(HglCheckService::class.java)?.reset()
        }
        super.doOKAction()
    }

    companion object {
        fun open(project: Project, selected: VirtualFile? = null) {
            val build = try { project.service<HglBuildFile>().read() } catch (error: IllegalArgumentException) {
                Messages.showErrorDialog(project, error.localizedMessage, "Configure HGL Test Roots")
                return
            }
            HglTestRootsDialog(project, build, selected).show()
        }
    }
}
