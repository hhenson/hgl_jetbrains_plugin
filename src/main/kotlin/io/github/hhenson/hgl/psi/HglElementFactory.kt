package io.github.hhenson.hgl.psi

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
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

    /** Member positions also accept keyword spellings (LEX-9). */
    fun createMemberName(project: Project, name: String): HglMemberName {
        val file = createFile(project, "module names\nenum E { $name }\n")
        return PsiTreeUtil.findChildOfType(file, HglEnumMember::class.java)?.memberName?.takeIf { it.text == name }
            ?: throw IncorrectOperationException("Invalid HGL member name: $name")
    }
}
