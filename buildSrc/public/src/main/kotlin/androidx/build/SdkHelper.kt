/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.build

import java.io.File
import java.util.Properties
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.FileTree

/** Writes the appropriate SDK path to local.properties file in specified location. */
fun Project.writeSdkPathToLocalPropertiesFile() {
    val sdkPath = project.getSdkPath()
    if (sdkPath.exists()) {
        val props = File(project.rootDir, "local.properties")
        // Gradle always separates directories with '/' regardless of the OS, so convert here.
        val gradlePath = sdkPath.absolutePath.replace(File.separator, "/")
        val contents = "sdk.dir=$gradlePath\ncmake.dir=$gradlePath/native-build-tools"
        props.printWriter().use { out -> out.println(contents) }
    } else {
        throw Exception(
            "Unable to find SDK prebuilts at $sdkPath. If you are not using a " +
                "standard repo-based checkout, please follow the checkout instructions at " +
                "go/androidx-onboarding."
        )
    }
}

/** Returns a file tree representing the platform SDK suitable for use as a dependency. */
fun Project.getSdkDependency(): FileTree =
    fileTree("${getSdkPath()}/platforms/android-${project.defaultAndroidConfig.compileSdk}/") {
        it.include("android.jar")
    }

/** Returns the root project's platform-specific SDK path as a file. */
fun Project.getSdkPath(): File {
    if (
        ProjectLayoutType.from(project) == ProjectLayoutType.PLAYGROUND ||
            System.getProperty("COMPOSE_DESKTOP_GITHUB_BUILD") != null
    ) {
        // This is not full checkout, use local settings instead.
        // https://developer.android.com/studio/command-line/variables
        // check for local.properties first
        val localPropsFile = rootProject.projectDir.resolve("local.properties")
        if (localPropsFile.exists()) {
            val localProps = Properties()
            localPropsFile.inputStream().use { localProps.load(it) }
            val localSdkDir = localProps["sdk.dir"]?.toString()
            if (localSdkDir != null) {
                val sdkDirectory = File(localSdkDir)
                if (sdkDirectory.isDirectory) {
                    return sdkDirectory
                }
            }
        }
        return getSdkPathFromEnvironmentVariable()
    }
    val os = getOperatingSystem()
    return if (os == OperatingSystem.WINDOWS) {
        getSdkPathFromEnvironmentVariable()
    } else {
        val platform = if (os == OperatingSystem.MAC) "darwin" else "linux"

        // By convention, the SDK prebuilts live under the root checkout directory.
        File(project.getCheckoutRoot(), "prebuilts/fullsdk-$platform")
    }
}

private fun getSdkPathFromEnvironmentVariable(): File {
    // check for environment variables, in the order AGP checks
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach {
        val envValue = System.getenv(it)
        if (envValue != null) {
            val sdkDirectory = File(envValue)
            if (sdkDirectory.isDirectory) {
                return sdkDirectory
            }
        }
    }
    // only print the error for SDK ROOT since ANDROID_HOME is deprecated but we first check
    // it because it is prioritized according to the documentation
    throw GradleException("ANDROID_SDK_ROOT environment variable is not set")
}

/** Sets the path to the canonical root project directory, e.g. {@code frameworks/support}. */
fun Project.setSupportRootFolder(rootDir: File?) {
    val extension = project.extensions.extraProperties
    return extension.set("supportRootFolder", rootDir)
}

/**
 * Returns the path to the canonical root project directory, e.g. {@code frameworks/support}.
 *
 * Note: This method of accessing the frameworks/support path is preferred over Project.rootDir
 * because it is generalized to also work for the "ui" project and playground projects.
 */
fun Project.getSupportRootFolder(): File {
    val extension = project.extensions.extraProperties
    return extension.get("supportRootFolder") as File
}

/**
 * Returns the path to the checkout's root directory, e.g. where {@code repo init} was run.
 *
 * <p>
 * This method assumes that the canonical root project directory is {@code frameworks/support}.
 */
fun Project.getCheckoutRoot(): File {
    return project.getSupportRootFolder().parentFile.parentFile
}

/** Returns the path to the konan prebuilts folder (e.g. <root>/prebuilts/androidx/konan). */
fun Project.getKonanPrebuiltsFolder(): File {
    return getPrebuiltsRoot().resolve("androidx/konan")
}
