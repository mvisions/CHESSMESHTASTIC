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
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    alias(libs.plugins.meshtastic.android.library)
}

dependencies {
    compileOnly(libs.kotlinx.coroutines.core)
}

val sdkDirectory = extensions.getByType<LibraryAndroidComponentsExtension>().sdkComponents.sdkDirectory
val executableSuffix = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val cmake = sdkDirectory.map { it.file("cmake/3.22.1/bin/cmake$executableSuffix").asFile }
val ninja = sdkDirectory.map { it.file("cmake/3.22.1/bin/ninja$executableSuffix").asFile }
val hostBuildDirectory = layout.buildDirectory.dir("host-native")
val hostLibraryDirectory = hostBuildDirectory.map { it.dir("lib") }

val configureHostNative by
    tasks.registering(Exec::class) {
        inputs.dir("src/main/cpp")
        outputs.file(hostBuildDirectory.map { it.file("CMakeCache.txt") })
        commandLine(
            cmake.get(),
            "-S",
            layout.projectDirectory.dir("src/main/cpp").asFile,
            "-B",
            hostBuildDirectory.get().asFile,
            "-G",
            "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${ninja.get()}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${hostLibraryDirectory.get().asFile}",
            "-DCMAKE_RUNTIME_OUTPUT_DIRECTORY=${hostLibraryDirectory.get().asFile}",
        )
    }

val buildHostNative by
    tasks.registering(Exec::class) {
        dependsOn(configureHostNative)
        inputs.dir("src/main/cpp")
        outputs.dir(hostLibraryDirectory)
        commandLine(cmake.get(), "--build", hostBuildDirectory.get().asFile, "--target", "vipbrotli", "--parallel", "2")
    }

configurations.create("hostNative") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(hostLibraryDirectory) { builtBy(buildHostNative) }
}

configure<LibraryExtension> {
    namespace = "org.meshtastic.core.brotli"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
