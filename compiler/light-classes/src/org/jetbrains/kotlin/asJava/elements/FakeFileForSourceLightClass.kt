/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.stubs.PsiClassHolderFileStub
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.psi.KtFile

open class FakeFileForSourceLightClass(
    ktFile: KtFile,
    lightClass: () -> KtLightClass,
    stub: () -> PsiClassHolderFileStub<*>
) : FakeFileForLightClass(ktFile, lightClass, stub, ktFile.packageFqName) {
    override fun findReferenceAt(offset: Int) = ktFile.findReferenceAt(offset)

    override fun processDeclarations(
        processor: PsiScopeProcessor,
        state: ResolveState,
        lastParent: PsiElement?,
        place: PsiElement
    ): Boolean {
        if (!super.processDeclarations(processor, state, lastParent, place)) return false

        // We have to explicitly process package declarations if current file belongs to default package
        // so that Java resolve can find classes located in that package
        val packageName = packageName
        if (!packageName.isEmpty()) return true

        val aPackage = JavaPsiFacade.getInstance(project).findPackage(packageName)
        if (aPackage != null && !aPackage.processDeclarations(processor, state, null, place)) return false

        return true
    }

}