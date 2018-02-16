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

package org.jetbrains.kotlin.asJava.builder

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ClassFileViewProvider
import com.intellij.psi.FileResolveScopeProvider
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.compiled.ClsFileImpl
import com.intellij.psi.impl.java.stubs.PsiJavaFileStub
import com.intellij.psi.impl.java.stubs.impl.PsiJavaFileStubImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.PsiClassHolderFileStub
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.MultiTargetPlatform
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.getMultiTargetPlatform

data class LightClassBuilderResult(val stub: PsiJavaFileStub, val bindingContext: BindingContext, val diagnostics: Diagnostics)

fun buildLightClass(
        packageFqName: FqName,
        files: Collection<KtFile>,
        generateClassFilter: GenerationState.GenerateClassFilter,
        context: LightClassConstructionContext,
        generate: (state: GenerationState, files: Collection<KtFile>) -> Unit
): LightClassBuilderResult {
    val project = files.first().project

    try {
        val classBuilderFactory = KotlinLightClassBuilderFactory(createJavaFileStub(project, packageFqName, files, context))
        val state = GenerationState.Builder(
                project,
                classBuilderFactory,
                context.module,
                context.bindingContext,
                files.toList(),
                CompilerConfiguration.EMPTY
        ).generateDeclaredClassFilter(generateClassFilter).wantsDiagnostics(false).build()
        state.beforeCompile()

        generate(state, files)

        val javaFileStub = classBuilderFactory.result()

        ServiceManager.getService(project, StubComputationTracker::class.java)?.onStubComputed(javaFileStub, context)
        return LightClassBuilderResult(javaFileStub, context.bindingContext, state.collectedExtraJvmDiagnostics)
    }
    catch (e: ProcessCanceledException) {
        throw e
    }
    catch (e: RuntimeException) {
        logErrorWithOSInfo(e, packageFqName, null)
        throw e
    }
}

private fun createJavaFileStub(
    project: Project,
    packageFqName: FqName,
    files: Collection<KtFile>,
    context: LightClassConstructionContext
): PsiJavaFileStub {
    val javaFileStub = PsiJavaFileStubImpl(packageFqName.asString(), /*compiled = */true)
    javaFileStub.psiFactory = ClsWrapperStubPsiFactory.INSTANCE

    val manager = PsiManager.getInstance(project)

    val virtualFile = getRepresentativeVirtualFile(files)
    val fakeFile = when (context.module.getMultiTargetPlatform()) {
        MultiTargetPlatform.Common -> FakeCommonClsFileImpl(javaFileStub, packageFqName, manager, virtualFile)
        else -> FakeClsFileImpl(javaFileStub, packageFqName, manager, virtualFile)
    }

    javaFileStub.psi = fakeFile
    return javaFileStub
}

private open class FakeClsFileImpl(
    private val javaFileStub: PsiClassHolderFileStub<*>,
    private val packageFqName: FqName,
    manager: PsiManager,
    virtualFile: VirtualFile
) : ClsFileImpl(ClassFileViewProvider(manager, virtualFile)) {
    override fun getStub() = javaFileStub

    override fun getPackageName() = packageFqName.asString()

    override fun isPhysical() = false
}

private class FakeCommonClsFileImpl(
    javaFileStub: PsiClassHolderFileStub<*>,
    packageFqName: FqName,
    manager: PsiManager,
    virtualFile: VirtualFile
) : FakeClsFileImpl(javaFileStub, packageFqName, manager, virtualFile), FileResolveScopeProvider {
    override fun getFileResolveScope(): GlobalSearchScope = GlobalSearchScope.allScope(manager.project)

    override fun ignoreReferencedElementAccessibility() = false
}

private fun getRepresentativeVirtualFile(files: Collection<KtFile>): VirtualFile {
    return files.first().viewProvider.virtualFile
}

private fun logErrorWithOSInfo(cause: Throwable?, fqName: FqName, virtualFile: VirtualFile?) {
    val path = if (virtualFile == null) "<null>" else virtualFile.path
    LOG.error(
            "Could not generate LightClass for $fqName declared in $path\n" +
            "System: ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} Java Runtime: ${SystemInfo.JAVA_RUNTIME_VERSION}",
            cause
    )
}

private val LOG = Logger.getInstance(LightClassBuilderResult::class.java)