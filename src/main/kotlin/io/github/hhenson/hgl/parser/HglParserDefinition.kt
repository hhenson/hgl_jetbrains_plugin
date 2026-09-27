package io.github.hhenson.hgl.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import io.github.hhenson.hgl.lexer.HglLexerAdapter
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFileElementType
import io.github.hhenson.hgl.psi.HglTokenSets
import io.github.hhenson.hgl.psi.HglTypes

class HglParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = HglLexerAdapter()

    override fun createParser(project: Project?): PsiParser = HglParser()

    override fun getFileNodeType(): IFileElementType = HglFileElementType

    /** NEWLINE is deliberately not whitespace: the grammar consumes it. */
    override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE

    override fun getCommentTokens(): TokenSet = HglTokenSets.COMMENTS

    override fun getStringLiteralElements(): TokenSet = HglTokenSets.STRINGS

    override fun createElement(node: ASTNode): PsiElement = HglTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = HglFile(viewProvider)
}
