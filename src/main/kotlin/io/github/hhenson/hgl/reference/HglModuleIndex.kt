package io.github.hhenson.hgl.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.psi.HglFile

/**
 * Maps a module path (`examples.market_data`) to the files that declare it,
 * so `use` declarations and `alias::name` references can cross files. The
 * key is read from the `module` line by a text scan, without parsing.
 */
class HglModuleIndex : ScalarIndexExtension<String>() {
    override fun getName(): ID<String, Void> = NAME

    override fun getIndexer(): DataIndexer<String, Void, FileContent> = DataIndexer { content ->
        val path = moduleNameOf(content.contentAsText)
        if (path == null) emptyMap() else mapOf(path to null)
    }

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(HglFileType)

    override fun dependsOnFileContent(): Boolean = true

    override fun getVersion(): Int = 1

    companion object {
        val NAME: ID<String, Void> = ID.create("io.github.hhenson.hgl.module")

        private val MODULE_LINE = Regex("""^\s*module\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*)""", RegexOption.MULTILINE)

        /** The declared module path of a source text, or null. Only the first `module` line counts. */
        fun moduleNameOf(text: CharSequence): String? =
            MODULE_LINE.find(text)?.groupValues?.get(1)?.filterNot { it.isWhitespace() }

        fun findFiles(project: Project, modulePath: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<HglFile> {
            val virtualFiles: Collection<VirtualFile> =
                FileBasedIndex.getInstance().getContainingFiles(NAME, modulePath, scope)
            val manager = PsiManager.getInstance(project)
            return virtualFiles.mapNotNull { manager.findFile(it) as? HglFile }
        }
    }
}
