/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider

/** Runs Android host tests against the same JNI codec, compiled for the build host rather than Android. */
fun Project.configureBrotliHostTests() {
    val nativeLibraries =
        configurations.create("brotliHostNative") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    dependencies.add(
        nativeLibraries.name,
        dependencies.project(mapOf("path" to ":core:brotli", "configuration" to "hostNative")),
    )
    val libraryPath = objects.newInstance<BrotliLibraryPath>()
    libraryPath.libraryDirectories.from(nativeLibraries)
    tasks
        .withType<Test>()
        .matching { it.name == "testAndroidHostTest" }
        .configureEach {
            dependsOn(nativeLibraries)
            jvmArgumentProviders.add(libraryPath)
        }
}

abstract class BrotliLibraryPath : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraryDirectories: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-Djava.library.path=${libraryDirectories.asPath}")
}
