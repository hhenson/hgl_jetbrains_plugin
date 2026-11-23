package io.github.hhenson.hgl.reference

import com.intellij.psi.TokenType
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.psi.HglTypes

/** Files containing an `impl [const] fn name` header. The gutter checks its PSI and binding. */
class HglImplementationIndex : ScalarIndexExtension<String>() {
    override fun getName() = NAME
    override fun getVersion() = 3
    override fun getKeyDescriptor() = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(HglFileType)
    override fun dependsOnFileContent() = true

    override fun getIndexer() = DataIndexer<String, Void, FileContent> { content ->
        val lexer = HglLexerAdapter()
        lexer.start(content.contentAsText)
        fun nextToken() {
            lexer.advance()
            while (lexer.tokenType == TokenType.WHITE_SPACE || HglTokenSets.COMMENTS.contains(lexer.tokenType)) lexer.advance()
        }
        val names = mutableMapOf<String, Void?>()
        while (lexer.tokenType != null) {
            if (lexer.tokenType != HglTypes.IMPL_KW) {
                lexer.advance()
                continue
            }
            nextToken()
            if (lexer.tokenType == HglTypes.IDENTIFIER && lexer.tokenText == "const") nextToken()
            if (lexer.tokenType != HglTypes.FN_KW) continue
            nextToken()
            if (lexer.tokenType == HglTypes.IDENTIFIER) names[lexer.tokenText] = null
        }
        names
    }

    companion object {
        val NAME: ID<String, Void> = ID.create("io.github.hhenson.hgl.implementation")
    }
}
