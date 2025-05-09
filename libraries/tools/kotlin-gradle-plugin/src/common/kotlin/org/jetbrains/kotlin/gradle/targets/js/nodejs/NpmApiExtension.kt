/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.nodejs

import org.gradle.api.file.FileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmApiExecution

/**
 * Represents an extension interface for managing and configuring an NPM-based project in a Kotlin/Gradle context.
 * Provides functionality to interact with the NPM package manager and handle its environment setup.
 *
 * @param Env The type of package manager environment implementation. Must extend [PackageManagerEnvironment].
 * @param NpmApi The type of NPM API execution implementation. Must extend [NpmApiExecution] with the given environment.
 */
interface NpmApiExtension<out Env : PackageManagerEnvironment, out NpmApi : NpmApiExecution<Env>> {
    val name: String

    val packageManager: NpmApi

    val environment: Env

    val additionalInstallOutput: FileCollection

    val preInstallTasks: ListProperty<TaskProvider<*>>

    val postInstallTasks: ListProperty<TaskProvider<*>>
}

typealias NpmApiExt = NpmApiExtension<PackageManagerEnvironment, NpmApiExecution<PackageManagerEnvironment>>