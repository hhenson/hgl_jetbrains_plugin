package io.github.hhenson.hgl.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglImportSet
import io.github.hhenson.hgl.psi.HglNativeContractBody
import io.github.hhenson.hgl.psi.HglStructBody
import io.github.hhenson.hgl.psi.HglTestContext
import io.github.hhenson.hgl.psi.HglTypes

class HglBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: com.intellij.psi.PsiFile, openingBraceOffset: Int): Int = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(HglTypes.LPAREN, HglTypes.RPAREN, false),
            BracePair(HglTypes.LBRACKET, HglTypes.RBRACKET, false),
            BracePair(HglTypes.LBRACE, HglTypes.RBRACE, true),
        )
    }
}

class HglCommenter : Commenter {
    override fun getLineCommentPrefix(): String = "# "

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}

/** Folds brace-delimited bodies, block comments and opaque C++ bodies. */
class HglFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = ArrayList<FoldingDescriptor>()
        PsiTreeUtil.processElements(root) { element ->
            val node = element.node
            val foldable = when {
                element is HglBlock || element is HglStructBody || element is HglNativeContractBody ||
                    element is HglTestContext || element is HglImportSet -> braceRange(node)
                node.elementType == HglTypes.BLOCK_COMMENT || node.elementType == HglTypes.CPP_BODY -> node.textRange
                else -> null
            }
            if (foldable != null && spansLines(document, foldable.startOffset, foldable.endOffset)) {
                descriptors.add(FoldingDescriptor(node, foldable))
            }
            true
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        HglTypes.BLOCK_COMMENT -> "/*...*/"
        else -> "{...}"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    /** The range from the first `{` to the last `}` of a body, or null when either is missing. */
    private fun braceRange(node: ASTNode): com.intellij.openapi.util.TextRange? {
        val open = node.findChildByType(HglTypes.LBRACE) ?: return null
        val close = node.lastChildNode?.takeIf { it.elementType == HglTypes.RBRACE } ?: return null
        return com.intellij.openapi.util.TextRange(open.startOffset, close.textRange.endOffset)
    }

    private fun spansLines(document: Document, start: Int, end: Int): Boolean =
        end > start && document.getLineNumber(start) < document.getLineNumber(end)
}
