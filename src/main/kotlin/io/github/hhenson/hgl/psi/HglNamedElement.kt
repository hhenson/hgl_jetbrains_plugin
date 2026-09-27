package io.github.hhenson.hgl.psi

import com.intellij.psi.PsiNameIdentifierOwner

/** A declaration that introduces a name: functions, structs, operators, tests, parameters, locals, imports. */
interface HglNamedElement : PsiNameIdentifierOwner
