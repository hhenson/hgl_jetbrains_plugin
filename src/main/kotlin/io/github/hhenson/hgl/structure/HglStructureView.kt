package io.github.hhenson.hgl.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.hhenson.hgl.psi.HglFile
import io.github.hhenson.hgl.psi.HglFunctionDecl
import io.github.hhenson.hgl.psi.HglNativeFunctionDecl
import io.github.hhenson.hgl.psi.HglOperatorDecl
import io.github.hhenson.hgl.psi.HglStructDecl
import io.github.hhenson.hgl.psi.HglStructMember
import io.github.hhenson.hgl.psi.HglTestContext
import io.github.hhenson.hgl.psi.HglTestDecl
import javax.swing.Icon

class HglStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile as? HglFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel = HglStructureViewModel(file, editor)
        }
    }
}

class HglStructureViewModel(file: HglFile, editor: Editor?) :
    StructureViewModelBase(file, editor, HglStructureViewElement(file)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = element.value is HglFile

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        element.value !is HglFile && element.value !is HglStructDecl && element.value !is HglTestContext
}

class HglStructureViewElement(private val element: NavigatablePsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) = element.navigate(requestFocus)

    override fun canNavigate(): Boolean = element.canNavigate()

    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()

    override fun getAlphaSortKey(): String = presentableName()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = presentableName()

        override fun getLocationString(): String? = when (element) {
            is HglFile -> element.modulePath
            is HglFunctionDecl, is HglNativeFunctionDecl, is HglOperatorDecl -> HglPresentation.tailOf(element)
            is HglStructMember -> HglPresentation.tailOf(element)
            else -> null
        }

        override fun getIcon(unused: Boolean): Icon = HglPresentation.iconOf(element)
    }

    override fun getChildren(): Array<TreeElement> {
        val children: List<PsiElement> = when (element) {
            is HglFile -> element.children.filter {
                it is HglStructDecl || it is HglFunctionDecl || it is HglNativeFunctionDecl ||
                    it is HglOperatorDecl || it is HglTestDecl || it is HglTestContext
            }
            is HglStructDecl -> element.structBody?.structMemberList ?: emptyList()
            is HglTestContext -> element.children.filter { it is HglFunctionDecl || it is HglTestDecl }
            else -> emptyList()
        }
        return children.filterIsInstance<NavigatablePsiElement>().map { HglStructureViewElement(it) }.toTypedArray()
    }

    private fun presentableName(): String = when (element) {
        is HglFile -> element.name
        is HglTestContext -> "test context"
        else -> element.name ?: element.text.lineSequence().first()
    }
}
