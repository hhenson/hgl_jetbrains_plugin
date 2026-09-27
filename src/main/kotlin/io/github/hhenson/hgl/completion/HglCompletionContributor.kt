package io.github.hhenson.hgl.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import io.github.hhenson.hgl.HglLanguage
import io.github.hhenson.hgl.psi.HglBlock
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglStructBody
import io.github.hhenson.hgl.psi.HglTypes

/**
 * Keyword completion. Names in scope come from the qualified-name
 * reference's variants, which the platform contributes on its own.
 */
class HglCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            psiElement().withLanguage(HglLanguage).andNot(psiElement().inside(PsiComment::class.java)),
            KeywordProvider,
        )
    }

    private object KeywordProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val position = parameters.position
            val previous = position.node.treePrev?.elementType
            if (previous == HglTypes.DOT || previous == HglTypes.COLONCOLON) return

            val inBlock = position.parentOfType<HglBlock>() != null
            val inStruct = position.parentOfType<HglStructBody>() != null
            val words = when {
                inBlock -> STATEMENT_KEYWORDS + EXPRESSION_KEYWORDS + TYPE_KEYWORDS
                inStruct -> TYPE_KEYWORDS
                position.containingFile is HglFile -> DECLARATION_KEYWORDS + TYPE_KEYWORDS
                else -> emptyList()
            }
            for (word in words) {
                result.addElement(LookupElementBuilder.create(word).bold())
            }
        }
    }

    companion object {
        val DECLARATION_KEYWORDS = listOf(
            "module", "part", "use", "as", "export", "abstract", "impl", "instantiate", "operator", "fn",
            "const", "cpp", "struct", "requires", "test", "native", "throws", "properties", "each", "in", "is",
        )
        val STATEMENT_KEYWORDS = listOf(
            "let", "var", "state", "cache", "inject", "return", "if", "else", "start", "when", "stop",
            "for", "in", "assert",
        )
        val EXPRESSION_KEYWORDS = listOf("fn", "eval", "const", "true", "false", "null", "delta")
        val TYPE_KEYWORDS = listOf(
            "bool", "i64", "f64", "str", "date", "time", "datetime", "duration", "civil_datetime",
            "zoned_datetime", "zoned_time", "timezone", "atomic", "tuple", "list", "set", "map", "rolling",
            "ref", "signal", "unbounded",
        )
    }
}
