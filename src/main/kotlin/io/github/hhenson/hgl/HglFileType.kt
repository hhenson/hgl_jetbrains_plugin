package io.github.hhenson.hgl

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object HglIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/hgl.svg", HglIcons::class.java)
}

object HglFileType : LanguageFileType(HglLanguage) {
    const val EXTENSION = "hgl"

    override fun getName(): String = "HGL"

    override fun getDescription(): String = "HGL temporal language module"

    override fun getDefaultExtension(): String = EXTENSION

    override fun getIcon(): Icon = HglIcons.FILE
}
