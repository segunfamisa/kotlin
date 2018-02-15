/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.FileResolveScopeProvider
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.PsiClassHolderFileStub
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.psi.KtFile

class FakeFileForCommonLightClass(
    ktFile: KtFile,
    lightClass: () -> KtLightClass,
    stub: () -> PsiClassHolderFileStub<*>
) : FakeFileForSourceLightClass(ktFile, lightClass, stub), FileResolveScopeProvider {
    override fun getFileResolveScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    override fun ignoreReferencedElementAccessibility() = false
}