package io.github.hhenson.hgl.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import java.io.File

internal fun hglCommandLine(executable: String): GeneralCommandLine =
    GeneralCommandLine(executable.trim())
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        .apply { if (SystemInfo.isMac) appendHomebrewBin("/opt/homebrew/bin") }

/** Finder-launched IDEs can also need this directory for the compiler's CMake and Ninja. */
internal fun GeneralCommandLine.appendHomebrewBin(bin: String) {
    val path = effectiveEnvironment["PATH"].orEmpty()
    if (bin !in path.split(File.pathSeparatorChar)) {
        withEnvironment("PATH", if (path.isEmpty()) bin else path + File.pathSeparator + bin)
    }
}
