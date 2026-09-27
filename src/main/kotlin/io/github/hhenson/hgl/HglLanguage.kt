package io.github.hhenson.hgl

import com.intellij.lang.Language

/** The HGL temporal programming language (hgraph_spec). */
object HglLanguage : Language("HGL") {
    private fun readResolve(): Any = HglLanguage

    override fun getDisplayName(): String = "HGL"
}
