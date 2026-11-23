package io.github.hhenson.hgl.reference

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.TokenType
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
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.psi.HglTypes

/**
 * Maps a module path (`examples.market_data`) to the files that declare it,
 * so `use` declarations and `alias::name` references can cross files. The
 * key is read from the header with the HGL lexer.
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

    override fun getVersion(): Int = 4

    companion object {
        val NAME: ID<String, Void> = ID.create("io.github.hhenson.hgl.module")

        // A benchmark samples one synchronous lookup, including repeat visits to a module part.
        @org.jetbrains.annotations.TestOnly
        internal val fileVisits = ThreadLocal<(Int) -> Unit>()

        /** Index importable module headers, including parts. */
        fun moduleNameOf(text: CharSequence): String? = headerOf(text)?.takeUnless { it.application }?.name

        internal data class Header(val name: String, val application: Boolean)

        internal fun headerOf(text: CharSequence): Header? {
            val lexer = HglLexerAdapter()
            lexer.start(text)
            fun skipTrivia() {
                while (lexer.tokenType == TokenType.WHITE_SPACE || lexer.tokenType == HglTypes.NEWLINE ||
                    HglTokenSets.COMMENTS.contains(lexer.tokenType)) lexer.advance()
            }
            skipTrivia()
            val application = when (lexer.tokenType) {
                HglTypes.IDENTIFIER -> if (lexer.tokenText == "application") true else return null
                HglTypes.MODULE_KW -> false
                else -> return null
            }
            lexer.advance()
            val path = mutableListOf<String>()
            while (true) {
                skipTrivia()
                if (lexer.tokenType != HglTypes.IDENTIFIER) return null
                path.add(lexer.tokenText)
                lexer.advance()
                skipTrivia()
                if (lexer.tokenType != HglTypes.DOT) return Header(path.joinToString("."), application)
                lexer.advance()
            }
        }

        fun findFiles(project: Project, modulePath: String, scope: GlobalSearchScope = GlobalSearchScope.allScope(project)): List<HglFile> {
            if (DumbService.isDumb(project)) return emptyList()
            val virtualFiles: Collection<VirtualFile> =
                FileBasedIndex.getInstance().getContainingFiles(NAME, modulePath, scope)
            fileVisits.get()?.invoke(virtualFiles.size)
            val manager = PsiManager.getInstance(project)
            return virtualFiles.mapNotNull { manager.findFile(it) as? HglFile }
        }
    }
}
