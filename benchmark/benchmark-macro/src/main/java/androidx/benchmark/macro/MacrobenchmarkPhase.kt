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

package androidx.benchmark.macro

import android.os.Build
import android.util.Log
import androidx.benchmark.Profiler
import androidx.benchmark.inMemoryTrace
import androidx.benchmark.perfetto.ExperimentalPerfettoCaptureApi
import androidx.benchmark.perfetto.PerfettoCapture
import androidx.benchmark.perfetto.PerfettoCaptureWrapper
import androidx.benchmark.perfetto.PerfettoConfig
import androidx.benchmark.perfetto.PerfettoTrace
import androidx.benchmark.perfetto.PerfettoTraceProcessor
import androidx.benchmark.perfetto.UiState
import androidx.benchmark.perfetto.appendUiState
import androidx.tracing.trace
import java.io.File

/**
 * A Profiler being used during a Macro Benchmark Phase.
 */
internal interface PhaseProfiler {
    /**
     * Starts a Phase profiler.
     */
    fun start()

    /**
     * Stops a Phase profiler.
     */
    fun stop(): List<Profiler.ResultFile>
}

/**
 * A [PhaseProfiler] that performs method tracing.
 */
internal class MethodTracingProfiler(
    private val scope: MacrobenchmarkScope
) : PhaseProfiler {
    override fun start() {
        scope.startMethodTracing()
    }

    override fun stop(): List<Profiler.ResultFile> {
        return scope.stopMethodTracing()
    }
}

/**
 * Results obtained from running a Macrobenchmark Phase.
 */
internal data class PhaseResult(
    /**
     * A list of Perfetto trace paths obtained. Typically a single trace in this list represents
     * one iteration of a Macrobenchmark Phase.
     */
    val tracePaths: List<String> = emptyList(),
    /**
     * A list of profiler results obtained during a Macrobenchmark Phase.
     */
    val profilerResults: List<Profiler.ResultFile> = emptyList(),
    /**
     * The list of measurements obtained per-iteration from the Macrobenchmark Phase.
     */
    val measurements: List<List<Metric.Measurement>> = emptyList()
)

/**
 * Run a Macrobenchmark Phase and collect the [PhaseResult].
 */
@ExperimentalPerfettoCaptureApi
internal fun PerfettoTraceProcessor.runPhase(
    uniqueName: String,
    packageName: String,
    macrobenchmarkPackageName: String,
    iterations: Int,
    startupMode: StartupMode?,
    scope: MacrobenchmarkScope,
    profiler: PhaseProfiler?,
    metrics: List<Metric>,
    perfettoConfig: PerfettoConfig?,
    perfettoSdkConfig: PerfettoCapture.PerfettoSdkConfig?,
    setupBlock: MacrobenchmarkScope.() -> Unit,
    measureBlock: MacrobenchmarkScope.() -> Unit
): PhaseResult {
    // Perfetto collector is separate from metrics, so we can control file
    // output, and give it different (test-wide) lifecycle
    val perfettoCollector = PerfettoCaptureWrapper()
    val tracePaths = mutableListOf<String>()
    val measurements = mutableListOf<List<Metric.Measurement>>()
    val profilerResultFiles = mutableListOf<Profiler.ResultFile>()
    try {
        // Configure metrics in the Phase.
        metrics.forEach {
            it.configure(packageName)
        }
        List(iterations) { iteration ->
            // Wake the device to ensure it stays awake with large iteration count
            inMemoryTrace("wake device") {
                scope.device.wakeUp()
            }

            scope.iteration = iteration

            inMemoryTrace("setupBlock") {
                setupBlock(scope)
            }

            // Setup file labels.
            val iterString = iteration.toString().padStart(3, '0')
            scope.fileLabel = "${uniqueName}_iter$iterString"

            val tracePath = perfettoCollector.record(
                fileLabel = scope.fileLabel,
                config = perfettoConfig ?: PerfettoConfig.Benchmark(
                    /**
                     * Prior to API 24, every package name was joined into a single setprop
                     * which can overflow, and disable *ALL* app level tracing.
                     *
                     * For safety here, we only trace the macrobench package on newer platforms,
                     * and use reflection in the macrobench test process to trace important
                     * sections
                     *
                     * @see androidx.benchmark.macro.perfetto.ForceTracing
                     */
                    appTagPackages = if (Build.VERSION.SDK_INT >= 24) {
                        listOf(packageName, macrobenchmarkPackageName)
                    } else {
                        listOf(packageName)
                    },
                    useStackSamplingConfig = true
                ),
                perfettoSdkConfig = perfettoSdkConfig,
                inMemoryTracingLabel = "Macrobenchmark"
            ) {
                try {
                    trace("start metrics") {
                        metrics.forEach {
                            it.start()
                        }
                        profiler?.start()
                        trace("measureBlock") {
                            measureBlock(scope)
                        }
                    }
                } finally {
                    trace("stop metrics") {
                        metrics.forEach {
                            it.stop()
                        }
                        // Keep track of Profiler Results.
                        profilerResultFiles += profiler?.stop() ?: emptyList()
                    }
                }
            }!!

            // Accumulate Trace Paths
            tracePaths.add(tracePath)

            // Append UI state to trace, so tools opening trace will highlight relevant
            // parts in UI.
            val uiState = UiState(
                highlightPackage = packageName
            )

            Log.d(TAG, "Iteration $iteration captured $uiState")
            File(tracePath).apply {
                appendUiState(uiState)
            }

            // Accumulate measurements
            measurements += loadTrace(PerfettoTrace(tracePath)) {
                // Extracts the metrics using the perfetto trace processor
                inMemoryTrace("extract metrics") {
                    metrics
                        // capture list of Measurements
                        .map {
                            it.getMeasurements(
                                Metric.CaptureInfo(
                                    targetPackageName = packageName,
                                    testPackageName = macrobenchmarkPackageName,
                                    startupMode = startupMode,
                                    apiLevel = Build.VERSION.SDK_INT
                                ),
                                this
                            )
                        }
                        // merge together
                        .reduceOrNull() { sum, element -> sum.merge(element) } ?: emptyList()
                }
            }
        }
    } finally {
        scope.killProcess()
    }
    return PhaseResult(
        tracePaths = tracePaths,
        profilerResults = profilerResultFiles,
        measurements = measurements
    )
}
