package io.github.hhenson.hgl

import com.intellij.lang.Language

/** The HGL temporal programming language. */
object HglLanguage : Language("HGL") {
    internal val endpointPredicates = setOf("modified", "valid", "all_valid")

    private fun readResolve(): Any = HglLanguage

    override fun getDisplayName(): String = "HGL"
}
