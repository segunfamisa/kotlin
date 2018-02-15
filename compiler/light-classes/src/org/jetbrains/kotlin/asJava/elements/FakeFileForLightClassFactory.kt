/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.stubs.PsiClassHolderFileStub
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.moduleInfo
import org.jetbrains.kotlin.resolve.TargetPlatform

object FakeFileForLightClassFactory {

    fun create(ktFile: KtFile, lightClass: () -> KtLightClass, stub: () -> PsiClassHolderFileStub<*>): FakeFileForSourceLightClass =
        when (ktFile.moduleInfo?.platform) {
            TargetPlatform.Common -> FakeFileForCommonLightClass(ktFile, lightClass, stub)
            else -> FakeFileForSourceLightClass(ktFile, lightClass, stub)
        }
}