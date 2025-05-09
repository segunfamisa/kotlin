/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.mpp

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.isAnnotationClass
import org.jetbrains.kotlin.mpp.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.name.StandardClassIds
import org.jetbrains.kotlin.resolve.calls.mpp.AbstractExpectActualMatcher.matchSingleExpectAgainstPotentialActuals
import org.jetbrains.kotlin.resolve.checkers.OptInNames
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualCheckingCompatibility
import org.jetbrains.kotlin.resolve.multiplatform.ExpectActualMatchingCompatibility
import org.jetbrains.kotlin.types.model.TypeSubstitutorMarker
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.enumMapOf
import org.jetbrains.kotlin.utils.addToStdlib.enumSetOf
import org.jetbrains.kotlin.utils.zipIfSizesAreEqual
import java.util.*

/**
 * This object is responsible for checking of expect-actual pairs
 * after they have been matched by [the matcher][AbstractExpectActualMatcher]
 *
 * See `/docs/fir/k2_kmp.md` for details
 */
object AbstractExpectActualChecker {
    fun <T : DeclarationSymbolMarker> getClassifiersCompatibility(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassLikeSymbol: ClassLikeSymbolMarker,
        context: ExpectActualMatchingContext<T>,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility<T> {
        val result = with(context) {
            getClassifiersCompatibility(
                expectClassSymbol,
                actualClassLikeSymbol,
                parentSubstitutor = null,
                languageVersionSettings,
            )
        }
        @Suppress("UNCHECKED_CAST")
        return result as ExpectActualCheckingCompatibility<T>
    }

    fun <T : DeclarationSymbolMarker> getCallablesCompatibility(
        expectDeclaration: CallableSymbolMarker,
        actualDeclaration: CallableSymbolMarker,
        expectContainingClass: RegularClassSymbolMarker?,
        actualContainingClass: RegularClassSymbolMarker?,
        context: ExpectActualMatchingContext<T>,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility<T> = with(context) {
        val expectTypeParameters = expectContainingClass?.typeParameters.orEmpty()
        val actualTypeParameters = actualContainingClass?.typeParameters.orEmpty()
        val parentSubstitutor = (expectTypeParameters zipIfSizesAreEqual actualTypeParameters)
            ?.let { createExpectActualTypeParameterSubstitutor(it, parentSubstitutor = null) }
        val result = getCallablesCompatibility(
            expectDeclaration,
            actualDeclaration,
            parentSubstitutor,
            expectContainingClass,
            actualContainingClass,
            languageVersionSettings,
        )
        @Suppress("UNCHECKED_CAST")
        result as ExpectActualCheckingCompatibility<T>
    }

    fun <T : DeclarationSymbolMarker> checkSingleExpectTopLevelDeclarationAgainstMatchedActual(
        expectDeclaration: DeclarationSymbolMarker,
        actualDeclaration: DeclarationSymbolMarker,
        context: ExpectActualMatchingContext<T>,
        languageVersionSettings: LanguageVersionSettings,
    ) {
        with(context) {
            checkSingleExpectAgainstMatchedActual(
                expectDeclaration,
                actualDeclaration,
                substitutor = null,
                expectClassSymbol = null,
                actualClassSymbol = null,
                incompatibleMembers = null,
                languageVersionSettings,
            )
        }
    }

    private fun ExpectActualMatchingContext<*>.getClassifiersCompatibility(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassLikeSymbol: ClassLikeSymbolMarker,
        parentSubstitutor: TypeSubstitutorMarker?,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility<*> {
        // Can't check FQ names here because nested expected class may be implemented via actual typealias's expansion with the other FQ name
        require(nameOf(expectClassSymbol) == nameOf(actualClassLikeSymbol)) {
            "This function should be invoked only for declarations with the same name: $expectClassSymbol, $actualClassLikeSymbol"
        }

        val actualClass = computeActualClassFromPotentialActualTypealias(
            expectClassSymbol,
            actualClassLikeSymbol,
            onNestedTypealias = { return ExpectActualCheckingCompatibility.NestedTypeAlias },
            // do not report extra error on erroneous typealias
            onErroneousTypealias = { return ExpectActualCheckingCompatibility.Compatible },
        )!!

        if (!areCompatibleClassKinds(expectClassSymbol, actualClass)) return ExpectActualCheckingCompatibility.ClassKind

        if (!equalBy(expectClassSymbol, actualClass) { listOf(it.isCompanion, it.isInner, it.isInlineOrValue) }) {
            return ExpectActualCheckingCompatibility.ClassModifiers
        }

        if (expectClassSymbol.isFun && (!actualClass.isFun || !actualClass.isSamInterface())) {
            return ExpectActualCheckingCompatibility.FunInterfaceModifier
        }

        val expectTypeParameterSymbols = expectClassSymbol.typeParameters
        val actualTypeParameterSymbols = actualClass.typeParameters
        if (expectTypeParameterSymbols.size != actualTypeParameterSymbols.size) {
            return ExpectActualCheckingCompatibility.ClassTypeParameterCount
        }

        if (!areCompatibleModalities(expectClassSymbol.modality, actualClass.modality)) {
            return ExpectActualCheckingCompatibility.Modality(expectClassSymbol.modality, actualClass.modality)
        }

        if (!areCompatibleClassVisibilities(expectClassSymbol, actualClass)) {
            return ExpectActualCheckingCompatibility.Visibility(expectClassSymbol.visibility, actualClass.visibility)
        }

        val substitutor = createExpectActualTypeParameterSubstitutor(
            (expectTypeParameterSymbols zipIfSizesAreEqual actualTypeParameterSymbols)
                ?: error("expect/actual type parameters sizes are checked earlier"),
            parentSubstitutor
        )

        if (!areCompatibleTypeParameterUpperBounds(expectTypeParameterSymbols, actualTypeParameterSymbols, substitutor)) {
            return ExpectActualCheckingCompatibility.ClassTypeParameterUpperBounds
        }

        getTypeParametersVarianceOrReifiedIncompatibility(expectTypeParameterSymbols, actualTypeParameterSymbols)
            ?.let { return it }

        if (!areCompatibleSupertypes(expectClassSymbol, actualClass, substitutor)) {
            return ExpectActualCheckingCompatibility.Supertypes
        }

        if (isIllegalRequiresOptInAnnotation(on = actualClass, expectClassSymbol, languageVersionSettings)) {
            return ExpectActualCheckingCompatibility.IllegalRequiresOpt
        }

        getClassScopesIncompatibility(expectClassSymbol, actualClass, substitutor, languageVersionSettings)?.let { return it }

        return ExpectActualCheckingCompatibility.Compatible
    }

    private inline fun ExpectActualMatchingContext<*>.computeActualClassFromPotentialActualTypealias(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassLikeSymbol: ClassLikeSymbolMarker,
        onNestedTypealias: () -> Nothing?,
        onErroneousTypealias: () -> Nothing?
    ): RegularClassSymbolMarker? = when (actualClassLikeSymbol) {
        is RegularClassSymbolMarker -> actualClassLikeSymbol
        is TypeAliasSymbolMarker -> if (actualClassLikeSymbol.classId.isNestedClass) {
            onNestedTypealias()
        } else {
            // do not report extra error on erroneous typealias
            actualClassLikeSymbol.expandToRegularClass() ?: onErroneousTypealias()
        }
        else -> error("Incorrect actual classifier for $expectClassSymbol: $actualClassLikeSymbol")
    }

    private fun ExpectActualMatchingContext<*>.areCompatibleSupertypes(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassSymbol: RegularClassSymbolMarker,
        substitutor: TypeSubstitutorMarker,
    ): Boolean {
        val expectSupertypes = expectClassSymbol.superTypes.filterNot { it.typeConstructor().isAnyConstructor() }
        val actualType = actualClassSymbol.defaultType
        return expectSupertypes.all { expectSupertype ->
            val expectType = substitutor.safeSubstitute(expectSupertype)
            isSubtypeOf(superType = expectType, subType = actualType) &&
                    !isSubtypeOf(superType = actualType, subType = expectType)
        }
    }

    private fun ExpectActualMatchingContext<*>.getClassScopesIncompatibility(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassSymbol: RegularClassSymbolMarker,
        substitutor: TypeSubstitutorMarker,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility.Incompatible<*>? {
        val mismatchedMembers: ArrayList<Pair<DeclarationSymbolMarker, Map<ExpectActualMatchingCompatibility.Mismatch, List<DeclarationSymbolMarker?>>>> =
            ArrayList()
        val incompatibleMembers: ArrayList<Pair<DeclarationSymbolMarker, Map<ExpectActualCheckingCompatibility.Incompatible<*>, List<DeclarationSymbolMarker?>>>> =
            ArrayList()

        val actualMembersByName = actualClassSymbol.collectAllMembers(isActualDeclaration = true).groupBy { nameOf(it) }
        val expectMembers = expectClassSymbol.collectAllMembers(isActualDeclaration = false)
            // private expect constructors are yet allowed KT-68688
            .filterNot { it is CallableSymbolMarker && it !is ConstructorSymbolMarker && it.visibility == Visibilities.Private }
        matchAndCheckExpectMembersAgainstPotentialActuals(
            expectClassSymbol,
            actualClassSymbol,
            substitutor,
            languageVersionSettings,
            expectMembers,
            actualMembersByName,
            outToMismatchedMembers = mismatchedMembers,
            outToIncompatibleMembers = incompatibleMembers
        )

        val actualStaticMembersByName = actualClassSymbol.collectAllStaticCallables(isActualDeclaration = true).groupBy { nameOf(it) }
        val expectStaticMembers = expectClassSymbol.collectAllStaticCallables(isActualDeclaration = false)
        matchAndCheckExpectMembersAgainstPotentialActuals(
            expectClassSymbol,
            actualClassSymbol,
            substitutor,
            languageVersionSettings,
            expectStaticMembers,
            actualStaticMembersByName,
            outToMismatchedMembers = mismatchedMembers,
            outToIncompatibleMembers = incompatibleMembers
        )

        if (expectClassSymbol.classKind == ClassKind.ENUM_CLASS) {
            val aEntries = expectClassSymbol.collectEnumEntryNames()
            val bEntries = actualClassSymbol.collectEnumEntryNames()

            if (!bEntries.containsAll(aEntries)) return ExpectActualCheckingCompatibility.EnumEntries
        }

        // TODO: check static scope?

        return when (mismatchedMembers.isNotEmpty() || incompatibleMembers.isNotEmpty()) {
            true -> ExpectActualCheckingCompatibility.ClassScopes(mismatchedMembers, incompatibleMembers)
            false -> null
        }
    }

    private fun ExpectActualMatchingContext<*>.matchAndCheckExpectMembersAgainstPotentialActuals(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassSymbol: RegularClassSymbolMarker,
        substitutor: TypeSubstitutorMarker,
        languageVersionSettings: LanguageVersionSettings,
        expectMembers: List<DeclarationSymbolMarker>,
        actualMembersByName: Map<Name, List<DeclarationSymbolMarker>>,
        // out
        outToMismatchedMembers: ArrayList<Pair<DeclarationSymbolMarker, Map<ExpectActualMatchingCompatibility.Mismatch, List<DeclarationSymbolMarker?>>>>,
        outToIncompatibleMembers: ArrayList<Pair<DeclarationSymbolMarker, Map<ExpectActualCheckingCompatibility.Incompatible<*>, List<DeclarationSymbolMarker?>>>>
    ) {
        for (expectMember in expectMembers) {
            val actualMembers = getPossibleActualsByExpectName(expectMember, actualMembersByName)
            val matched = matchSingleExpectAgainstPotentialActuals(
                expectMember,
                actualMembers,
                substitutor,
                expectClassSymbol,
                actualClassSymbol,
                outToMismatchedMembers
            )
            for (it in matched) {
                checkSingleExpectAgainstMatchedActual(
                    expectMember,
                    it,
                    substitutor,
                    expectClassSymbol,
                    actualClassSymbol,
                    outToIncompatibleMembers,
                    languageVersionSettings
                )
            }
        }
    }

    private fun ExpectActualMatchingContext<*>.checkSingleExpectAgainstMatchedActual(
        expectMember: DeclarationSymbolMarker,
        actualMember: DeclarationSymbolMarker,
        substitutor: TypeSubstitutorMarker?,
        expectClassSymbol: RegularClassSymbolMarker?,
        actualClassSymbol: RegularClassSymbolMarker?,
        incompatibleMembers: MutableList<Pair<DeclarationSymbolMarker, Map<ExpectActualCheckingCompatibility.Incompatible<*>, List<DeclarationSymbolMarker?>>>>?,
        languageVersionSettings: LanguageVersionSettings,
    ) {
        val compatibility = when {
            skipCheckingOnExpectActualPair(expectMember, actualMember) -> {
                if (expectMember is RegularClassSymbolMarker && actualMember is ClassLikeSymbolMarker) {
                    computeActualClassFromPotentialActualTypealias(
                        expectMember,
                        actualMember,
                        onNestedTypealias = { null },
                        onErroneousTypealias = { null }
                    )?.let { actualClass ->
                        val parentSubstitutor = substitutor?.takeIf { !innerClassesCapturesOuterTypeParameters }
                        val substitutor = createExpectActualTypeParameterSubstitutor(
                            (expectMember.typeParameters zipIfSizesAreEqual actualClass.typeParameters)
                                ?: error("expect/actual type parameters sizes are checked earlier"),
                            parentSubstitutor
                        )

                        // Here we call check for two classes only to match the scopes of these classes, so the return value is ignored.
                        // Abstraction of matching leaked into checking in this place :sad:
                        getClassScopesIncompatibility(expectMember, actualClass, substitutor, languageVersionSettings)
                    }
                }
                ExpectActualCheckingCompatibility.Compatible
            }

            expectMember is CallableSymbolMarker -> getCallablesCompatibility(
                expectMember,
                actualMember as CallableSymbolMarker,
                substitutor,
                expectClassSymbol,
                actualClassSymbol,
                languageVersionSettings,
            )

            expectMember is RegularClassSymbolMarker -> {
                val parentSubstitutor = substitutor?.takeIf { !innerClassesCapturesOuterTypeParameters }
                getClassifiersCompatibility(
                    expectMember,
                    actualMember as ClassLikeSymbolMarker,
                    parentSubstitutor,
                    languageVersionSettings,
                )
            }
            else -> error("Unsupported declaration: $expectMember ($actualMember)")
        }

        val incompatibilityMap = mutableMapOf<ExpectActualCheckingCompatibility.Incompatible<*>, MutableList<DeclarationSymbolMarker>>()
        when (compatibility) {
            ExpectActualCheckingCompatibility.Compatible -> return
            is ExpectActualCheckingCompatibility.Incompatible<*> -> incompatibilityMap.getOrPut(compatibility) { SmartList() }.add(actualMember)
        }

        incompatibleMembers?.add(expectMember to incompatibilityMap)
        onIncompatibleMembersFromClassScope(expectMember, incompatibilityMap, expectClassSymbol, actualClassSymbol)
    }

    private fun ExpectActualMatchingContext<*>.getCallablesCompatibility(
        expectDeclaration: CallableSymbolMarker,
        actualDeclaration: CallableSymbolMarker,
        parentSubstitutor: TypeSubstitutorMarker?,
        expectContainingClass: RegularClassSymbolMarker?,
        actualContainingClass: RegularClassSymbolMarker?,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility<*> {
        checkCallablesInvariants(expectDeclaration, actualDeclaration)

        if (areEnumConstructors(expectDeclaration, actualDeclaration, expectContainingClass, actualContainingClass)) {
            return ExpectActualCheckingCompatibility.Compatible
        }

        val insideAnnotationClass = expectContainingClass?.classKind == ClassKind.ANNOTATION_CLASS
        val expectedTypeParameters = expectDeclaration.typeParameters
        val actualTypeParameters = actualDeclaration.typeParameters
        val expectedValueParameters = expectDeclaration.valueParameters
        val actualValueParameters = actualDeclaration.valueParameters
        val expectedContextParameters = expectDeclaration.contextParameters
        val actualContextParameters = actualDeclaration.contextParameters

        val substitutor = createExpectActualTypeParameterSubstitutor(
            (expectedTypeParameters zipIfSizesAreEqual actualTypeParameters)
                ?: error("expect/actual type parameters sizes are checked earlier"),
            parentSubstitutor
        )

        if (!areCompatibleExpectActualTypes(
                substitutor.safeSubstitute(expectDeclaration.returnType),
                actualDeclaration.returnType,
                parameterOfAnnotationComparisonMode = insideAnnotationClass,
                dynamicTypesEqualToAnything = false
            )
        ) {
            return ExpectActualCheckingCompatibility.ReturnType
        }

        if (
            actualDeclaration.hasStableParameterNames &&
            expectDeclaration.hasStableParameterNames &&
            !equalsBy(expectedValueParameters, actualValueParameters) { nameOf(it) }
        ) {
            return ExpectActualCheckingCompatibility.ParameterNames
        }

        if (
            languageVersionSettings.supportsFeature(LanguageFeature.ContextParameters) &&
            actualDeclaration.hasStableParameterNames &&
            expectDeclaration.hasStableParameterNames &&
            !equalsBy(expectedContextParameters, actualContextParameters) { nameOf(it) }
        ) {
            return ExpectActualCheckingCompatibility.ContextParameterNames
        }

        if (!equalsBy(expectedTypeParameters, actualTypeParameters) { nameOf(it) }) {
            return ExpectActualCheckingCompatibility.TypeParameterNames
        }

        val expectModality = expectDeclaration.modality
        val actualModality = actualDeclaration.modality
        if (
            !areCompatibleModalities(
                expectModality,
                actualModality,
                expectContainingClass?.modality,
                actualContainingClass?.modality
            )
        ) {
            return ExpectActualCheckingCompatibility.Modality(expectModality, actualModality)
        }

        if (!areCompatibleCallableVisibilities(
                expectDeclaration.visibility,
                expectModality,
                expectContainingClass?.modality,
                actualDeclaration.visibility,
                languageVersionSettings
            )
        ) {
            return ExpectActualCheckingCompatibility.Visibility(expectDeclaration.visibility, actualDeclaration.visibility)
        }

        getTypeParametersVarianceOrReifiedIncompatibility(expectedTypeParameters, actualTypeParameters)?.let { return it }

        if (languageVersionSettings.supportsFeature(LanguageFeature.ProhibitDefaultArgumentsInExpectActualizedByFakeOverride) &&
            // If expect declaration is a fake-override then default params came from common
            // supertypes of actual class and expect class. It's a valid code.
            !expectDeclaration.isFakeOverride(expectContainingClass) &&
            (actualDeclaration.isFakeOverride(actualContainingClass) || actualDeclaration.isDelegatedMember) &&
            expectDeclaration.valueParameters.any { it.hasDefaultValueNonRecursive }
        ) {
            return ExpectActualCheckingCompatibility.DefaultArgumentsInExpectActualizedByFakeOverride
        }

        if (shouldCheckDefaultParams &&
            // "parameters" checks are required only for functions, because only functions can have parameters
            actualDeclaration is FunctionSymbolMarker && expectDeclaration is FunctionSymbolMarker &&
            // Actual annotation constructors can have default argument values; their consistency with arguments in the expected annotation
            // is checked in ExpectedActualDeclarationChecker.checkAnnotationConstructors
            !actualDeclaration.isAnnotationConstructor()
        ) {
            val expectOverriddenDeclarations =
                expectDeclaration.allRecursivelyOverriddenDeclarationsIncludingSelf(expectContainingClass).toSet()
            val actualOverriddenDeclarations =
                actualDeclaration.allRecursivelyOverriddenDeclarationsIncludingSelf(actualContainingClass)

            // If default params came from common supertypes of actual class and expect class then it's a valid code.
            // Here we filter out such default params.
            if ((actualOverriddenDeclarations - expectOverriddenDeclarations).flatMap { it.valueParameters }.any { it.hasDefaultValue }) {
                return ExpectActualCheckingCompatibility.ActualFunctionWithDefaultParameters
            }
        }

        if (!equalsBy(expectedValueParameters, actualValueParameters) { it.isVararg }) {
            return ExpectActualCheckingCompatibility.ValueParameterVararg
        }

        // Adding noinline/crossinline to parameters is disallowed, except if the expected declaration was not inline at all
        if (expectDeclaration is SimpleFunctionSymbolMarker && expectDeclaration.isInline) {
            if (expectedValueParameters.indices.any { i -> !expectedValueParameters[i].isNoinline && actualValueParameters[i].isNoinline }) {
                return ExpectActualCheckingCompatibility.ValueParameterNoinline
            }
            if (expectedValueParameters.indices.any { i -> !expectedValueParameters[i].isCrossinline && actualValueParameters[i].isCrossinline }) {
                return ExpectActualCheckingCompatibility.ValueParameterCrossinline
            }
        }

        when {
            expectDeclaration is FunctionSymbolMarker && actualDeclaration is FunctionSymbolMarker ->
                getFunctionsIncompatibility(expectDeclaration, actualDeclaration)?.let { return it }

            expectDeclaration is PropertySymbolMarker && actualDeclaration is PropertySymbolMarker ->
                getPropertiesIncompatibility(expectDeclaration, actualDeclaration, expectContainingClass, languageVersionSettings)?.let { return it }

            expectDeclaration is EnumEntrySymbolMarker && actualDeclaration is EnumEntrySymbolMarker -> {
                // do nothing, entries are matched only by name
            }

            actualDeclaration.isJavaField && expectDeclaration.canBeActualizedByJavaField -> {
                // no specific checks, actualization by Java field is permitted in a limited well-known number of cases
            }

            else -> error("Unsupported declarations: $expectDeclaration, $actualDeclaration")
        }

        return ExpectActualCheckingCompatibility.Compatible
    }

    private fun ExpectActualMatchingContext<*>.areCompatibleClassKinds(
        expectClass: RegularClassSymbolMarker,
        actualClass: RegularClassSymbolMarker,
    ): Boolean {
        if (expectClass.classKind == actualClass.classKind) return true

        if (expectClass.classKind == ClassKind.CLASS && isFinal(expectClass) && isCtorless(expectClass)) {
            if (actualClass.classKind == ClassKind.OBJECT) return true
        }

        return false
    }

    private fun areCompatibleModalities(
        expectModality: Modality?,
        actualModality: Modality?,
        expectContainingClassModality: Modality? = null,
        actualContainingClassModality: Modality? = null,
    ): Boolean {
        val expectEffectiveModality = effectiveModality(expectModality, expectContainingClassModality)
        val actualEffectiveModality = effectiveModality(actualModality, actualContainingClassModality)

        return actualEffectiveModality in compatibleModalityMap.getValue(expectEffectiveModality)
    }

    /*
     * If containing class is final then all declarations in it effectively final
     */
    private fun effectiveModality(declarationModality: Modality?, containingClassModality: Modality?): Modality? {
        return when (containingClassModality) {
            Modality.FINAL -> Modality.FINAL
            else -> declarationModality
        }
    }

    /*
     * Key is expect modality, value is a set of compatible actual modalities
     */
    private val compatibleModalityMap: EnumMap<Modality, EnumSet<Modality>> = enumMapOf(
        Modality.ABSTRACT to enumSetOf(Modality.ABSTRACT),
        Modality.OPEN to enumSetOf(Modality.OPEN),
        Modality.FINAL to enumSetOf(Modality.OPEN, Modality.FINAL),
        Modality.SEALED to enumSetOf(Modality.SEALED),
    )

    private fun areCompatibleCallableVisibilities(
        expectVisibility: Visibility,
        expectModality: Modality?,
        expectContainingClassModality: Modality?,
        actualVisibility: Visibility,
        languageVersionSettings: LanguageVersionSettings
    ): Boolean {
        // In the case of actualization by a Java declaration such as a field or a method normalize the Java visibility
        // to the closest Kotlin visibility.Example: "protected_and_package" -> "protected".
        val normalizedExpectVisibility = expectVisibility.normalize()
        val normalizedActualVisibility = actualVisibility.normalize()

        val compare = Visibilities.compare(normalizedExpectVisibility, normalizedActualVisibility)

        val effectiveModality =
            when (languageVersionSettings.supportsFeature(LanguageFeature.SupportEffectivelyFinalInExpectActualVisibilityCheck)) {
                true -> effectiveModality(expectModality, expectContainingClassModality)
                false -> expectModality
            }
        return if (effectiveModality != Modality.FINAL) {
            // For overridable declarations visibility should match precisely, see KT-19664
            compare == 0
        } else {
            // For non-overridable declarations actuals are allowed to have more permissive visibility
            compare != null && compare <= 0
        }
    }

    private fun ExpectActualMatchingContext<*>.areCompatibleClassVisibilities(
        expectClassSymbol: RegularClassSymbolMarker,
        actualClassSymbol: RegularClassSymbolMarker,
    ): Boolean {
        val expectVisibility = expectClassSymbol.visibility
        val actualVisibility = actualClassSymbol.visibility
        if (expectVisibility == actualVisibility) return true
        val result = Visibilities.compare(actualVisibility, expectVisibility)
        return result != null && result > 0
    }

    private fun ExpectActualMatchingContext<*>.getTypeParametersVarianceOrReifiedIncompatibility(
        expectTypeParameterSymbols: List<TypeParameterSymbolMarker>,
        actualTypeParameterSymbols: List<TypeParameterSymbolMarker>,
    ): ExpectActualCheckingCompatibility.Incompatible<*>? {
        if (!equalsBy(expectTypeParameterSymbols, actualTypeParameterSymbols) { it.variance }) {
            return ExpectActualCheckingCompatibility.TypeParameterVariance
        }

        // Removing "reified" from an expected function's type parameter is fine
        if (
            expectTypeParameterSymbols.indices.any { i ->
                !expectTypeParameterSymbols[i].isReified && actualTypeParameterSymbols[i].isReified
            }
        ) {
            return ExpectActualCheckingCompatibility.TypeParameterReified
        }

        return null
    }

    private fun ExpectActualMatchingContext<*>.getFunctionsIncompatibility(
        expectFunction: CallableSymbolMarker,
        actualFunction: CallableSymbolMarker,
    ): ExpectActualCheckingCompatibility.Incompatible<*>? {
        if (!equalBy(expectFunction, actualFunction) { f -> f.isSuspend }) {
            return ExpectActualCheckingCompatibility.FunctionModifiersDifferent
        }

        if (
            expectFunction.isInfix && !actualFunction.isInfix ||
            expectFunction.isInline && !actualFunction.isInline ||
            expectFunction.isOperator && !actualFunction.isOperator
        ) {
            return ExpectActualCheckingCompatibility.FunctionModifiersNotSubset
        }

        return null
    }

    private fun ExpectActualMatchingContext<*>.getPropertiesIncompatibility(
        expected: PropertySymbolMarker,
        actual: PropertySymbolMarker,
        expectContainingClass: RegularClassSymbolMarker?,
        languageVersionSettings: LanguageVersionSettings,
    ): ExpectActualCheckingCompatibility.Incompatible<*>? {
        return when {
            !equalBy(expected, actual) { p -> p.isVar } -> ExpectActualCheckingCompatibility.PropertyKind
            !equalBy(expected, actual) { p -> p.isLateinit } -> ExpectActualCheckingCompatibility.PropertyLateinitModifier
            expected.isConst && !actual.isConst -> ExpectActualCheckingCompatibility.PropertyConstModifier
            !arePropertySettersWithCompatibleVisibilities(expected, actual, expectContainingClass, languageVersionSettings) ->
                ExpectActualCheckingCompatibility.PropertySetterVisibility(expected.setter?.visibility, actual.setter?.visibility)
            else -> null
        }
    }

    private fun ExpectActualMatchingContext<*>.arePropertySettersWithCompatibleVisibilities(
        expected: PropertySymbolMarker,
        actual: PropertySymbolMarker,
        expectContainingClass: RegularClassSymbolMarker?,
        languageVersionSettings: LanguageVersionSettings,
    ): Boolean {
        val expectedSetter = expected.setter ?: return true
        val actualSetter = actual.setter ?: return true
        return areCompatibleCallableVisibilities(
            expectedSetter.visibility,
            expectedSetter.modality,
            expectContainingClass?.modality,
            actualSetter.visibility,
            languageVersionSettings,
        )
    }

    // ---------------------------------------- Utils ----------------------------------------

    private inline fun <T, K> equalsBy(first: List<T>, second: List<T>, selector: (T) -> K): Boolean {
        for (i in first.indices) {
            if (selector(first[i]) != selector(second[i])) return false
        }

        return true
    }

    private inline fun <T, K> equalBy(first: T, second: T, selector: (T) -> K): Boolean =
        selector(first) == selector(second)

    private fun ExpectActualMatchingContext<*>.isCtorless(regularClass: RegularClassSymbolMarker): Boolean {
        return regularClass.getCallablesForExpectClass(SpecialNames.INIT).isEmpty()
    }

    private fun ExpectActualMatchingContext<*>.isFinal(regularClassSymbolMarker: RegularClassSymbolMarker): Boolean {
        return regularClassSymbolMarker.modality == Modality.FINAL
    }
}

fun ExpectActualMatchingContext<*>.isIllegalRequiresOptInAnnotation(
    on: RegularClassSymbolMarker, // actual or expect
    expect: RegularClassSymbolMarker,
    languageVersionSettings: LanguageVersionSettings,
): Boolean {
    return languageVersionSettings.supportsFeature(LanguageFeature.MultiplatformRestrictions) &&
            on.classKind.isAnnotationClass &&
            expect.annotations.none { it.classId == StandardClassIds.Annotations.OptionalExpectation } &&
            on.annotations.any { it.classId == OptInNames.REQUIRES_OPT_IN_CLASS_ID }
}
