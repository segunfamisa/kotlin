/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.uast.kotlin.declarations

import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.asJava.elements.KtLightIdentifier
import org.jetbrains.kotlin.psi.KtCallElement
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UIdentifier
import org.jetbrains.uast.kotlin.unwrapFakeFileForLightClass
import org.jetbrains.uast.toUElement

class UastLightIdentifier(lightOwner: PsiNameIdentifierOwner, ktDeclaration: KtNamedDeclaration?) :
    KtLightIdentifier(lightOwner, ktDeclaration) {
    override fun getContainingFile(): PsiFile = unwrapFakeFileForLightClass(super.getContainingFile())
}

class KotlinUIdentifier private constructor(
    override val javaPsi: PsiElement?,
    override val sourcePsi: PsiElement?,
    override val psi: PsiElement?,
    givenParent: UElement?
) : UIdentifier(psi, givenParent) {

    init {
        if (ApplicationManager.getApplication().isUnitTestMode)
            assert(sourcePsi == null || sourcePsi is LeafPsiElement || (sourcePsi is KtElement && sourcePsi.firstChild == null),
                   { "sourcePsi should be physical leaf element but got $sourcePsi of (${sourcePsi?.javaClass})" })
    }

    override val uastParent: UElement? by lazy {
        if (givenParent != null) return@lazy givenParent
        val parent = sourcePsi?.parent ?: return@lazy null
        val parentParent = parent.parent
        if (parentParent is KtCallElement && parentParent.calleeExpression == parent) { // method identifiers in calls
            return@lazy parentParent.toUElement()
        }
        return@lazy parent.toUElement()
    }

    constructor(javaPsi: PsiElement?, sourcePsi: PsiElement?, uastParent: UElement?) : this(javaPsi, sourcePsi, javaPsi, uastParent)
    constructor(sourcePsi: PsiElement?, uastParent: UElement?) : this(null, sourcePsi, sourcePsi, uastParent)
}