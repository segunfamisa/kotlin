/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.renderer.declarations.renderers.callables

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaDeclarationRenderer
import org.jetbrains.kotlin.analysis.api.symbols.KaSyntheticJavaPropertySymbol
import org.jetbrains.kotlin.analysis.utils.printer.PrettyPrinter
import org.jetbrains.kotlin.lexer.KtTokens

@KaExperimentalApi
public interface KaSyntheticJavaPropertySymbolRenderer {
    public fun renderSymbol(
        analysisSession: KaSession,
        symbol: KaSyntheticJavaPropertySymbol,
        declarationRenderer: KaDeclarationRenderer,
        printer: PrettyPrinter,
    )

    @KaExperimentalApi
    public object AS_SOURCE : KaSyntheticJavaPropertySymbolRenderer {
        override fun renderSymbol(
            analysisSession: KaSession,
            symbol: KaSyntheticJavaPropertySymbol,
            declarationRenderer: KaDeclarationRenderer,
            printer: PrettyPrinter,
        ) {
            printer {
                val mutabilityKeyword = if (symbol.isVal) KtTokens.VAL_KEYWORD else KtTokens.VAR_KEYWORD
                declarationRenderer.callableSignatureRenderer
                    .renderCallableSignature(analysisSession, symbol, mutabilityKeyword, declarationRenderer, printer)

                declarationRenderer.variableInitializerRenderer.renderInitializer(analysisSession, symbol, printer)
                declarationRenderer.propertyAccessorsRenderer.renderAccessors(analysisSession, symbol, declarationRenderer, printer)
            }
        }
    }
}
