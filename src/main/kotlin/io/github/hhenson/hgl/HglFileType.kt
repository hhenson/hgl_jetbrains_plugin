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

/** The compiler materializes bundled sources with this suffix in a debug build. */
object HglDebugSourceFileType : LanguageFileType(HglLanguage, true) {
    override fun getName() = "HGL Debug Source"
    override fun getDescription() = "HGL debugger source copy"
    override fun getDefaultExtension() = "hgl.source"
    override fun getIcon(): Icon = HglIcons.FILE
}
