package io.github.hhenson.hgl.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.HglFileType
import io.github.hhenson.hgl.HglLanguage

class HglFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, HglLanguage) {
    override fun getFileType(): FileType = HglFileType

    override fun toString(): String = "HGL file"

    /** The `module a.b.c` declaration, when the file has one. */
    val moduleDecl: HglModuleDecl?
        get() = PsiTreeUtil.getChildOfType(this, HglModuleDecl::class.java)

    /** The dotted module path, or null when the file declares none. */
    val modulePath: String?
        get() = moduleDecl?.modulePath?.text?.filterNot { it.isWhitespace() }

    val useDecls: List<HglUseDecl>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, HglUseDecl::class.java)
}
