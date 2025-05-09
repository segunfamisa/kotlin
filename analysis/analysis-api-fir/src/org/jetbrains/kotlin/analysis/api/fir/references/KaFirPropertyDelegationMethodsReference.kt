/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.fir.references

import org.jetbrains.kotlin.analysis.api.fir.KaFirSession
import org.jetbrains.kotlin.analysis.api.fir.buildSymbol
import org.jetbrains.kotlin.analysis.api.fir.getCalleeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.analysis.low.level.api.fir.api.getOrBuildFirSafe
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirFunctionCallOrigin
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.idea.references.KtPropertyDelegationMethodsReference
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtImportAlias
import org.jetbrains.kotlin.psi.KtPropertyDelegate

internal class KaFirPropertyDelegationMethodsReference(
    element: KtPropertyDelegate,
) : KtPropertyDelegationMethodsReference(element), KaFirReference {
    override fun KaFirSession.computeSymbols(): Collection<KaSymbol> {
        val property = (expression.parent as? KtElement)?.getOrBuildFirSafe<FirProperty>(resolutionFacade) ?: return emptyList()
        if (property.delegate == null) return emptyList()

        val getValueSymbol = (property.getter?.singleStatementOfType<FirReturnExpression>()?.result as? FirFunctionCall)?.getCalleeSymbol()
        val setValueSymbol = (property.setter?.singleStatementOfType<FirReturnExpression>()?.result as? FirFunctionCall)?.getCalleeSymbol()
        val provideDelegateSymbol =
            (property.delegate as? FirFunctionCall)?.takeIf { it.origin == FirFunctionCallOrigin.Operator }?.getCalleeSymbol()

        return listOfNotNull(
            getValueSymbol?.fir?.buildSymbol(firSymbolBuilder),
            setValueSymbol?.fir?.buildSymbol(firSymbolBuilder),
            provideDelegateSymbol?.fir?.buildSymbol(firSymbolBuilder),
        )
    }

    private inline fun <reified S : FirStatement> FirPropertyAccessor.singleStatementOfType(): S? =
        body?.statements?.singleOrNull() as? S

    override fun isReferenceToImportAlias(alias: KtImportAlias): Boolean {
        return super<KaFirReference>.isReferenceToImportAlias(alias)
    }

}