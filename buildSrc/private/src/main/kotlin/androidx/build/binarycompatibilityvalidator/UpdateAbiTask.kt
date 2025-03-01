/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.build.binarycompatibilityvalidator

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class UpdateAbiTask : DefaultTask() {

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val shouldWriteVersionedApiFile: Property<Boolean>

    /** Text file from which API signatures will be read. */
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:InputFile
    abstract var inputApiLocation: Provider<File>

    /** Directory to which API signatures will be written. */
    @get:OutputDirectory
    abstract var outputDir: Provider<File>

    @TaskAction
    fun execute() {
        fileSystemOperations.copy {
            it.from(inputApiLocation)
            it.into(outputDir)
        }
        if (shouldWriteVersionedApiFile.get()) {
            fileSystemOperations.copy {
                it.from(inputApiLocation) {}
                it.into(outputDir)
                it.rename(CURRENT_API_FILE_NAME, version.get())
            }
        }
    }
}
