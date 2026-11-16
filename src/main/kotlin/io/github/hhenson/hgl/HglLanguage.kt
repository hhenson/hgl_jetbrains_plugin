package io.github.hhenson.hgl

import com.intellij.lang.Language

/** The HGL temporal programming language. */
object HglLanguage : Language("HGL") {
    private fun readResolve(): Any = HglLanguage

    override fun getDisplayName(): String = "HGL"
}
