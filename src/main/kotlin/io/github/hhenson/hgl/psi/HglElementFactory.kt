package io.github.hhenson.hgl.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.hhenson.hgl.HglFileType

/** Builds PSI fragments by parsing a throw-away module. Used by rename. */
object HglElementFactory {
    fun createFile(project: Project, text: String): HglFile =
        PsiFileFactory.getInstance(project).createFileFromText("dummy.hgl", HglFileType, text) as HglFile

    fun createIdent(project: Project, name: String): HglIdent {
        val file = createFile(project, "module $name\n")
        val moduleDecl = file.moduleDecl ?: error("module declaration expected")
        return PsiTreeUtil.findChildOfType(moduleDecl.modulePath, HglIdent::class.java)
            ?: error("name expected")
    }
}
