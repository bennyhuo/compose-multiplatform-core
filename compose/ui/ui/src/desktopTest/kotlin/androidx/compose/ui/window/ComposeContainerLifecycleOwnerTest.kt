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

package androidx.compose.ui.window

import androidx.compose.ui.assertThat
import androidx.compose.ui.isEqualTo
import androidx.compose.ui.scene.ComposeContainer
import androidx.lifecycle.Lifecycle
import app.cash.turbine.test
import javax.swing.JFrame
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.jetbrains.skiko.SkiaLayerAnalytics
import org.junit.Test

class ComposeContainerLifecycleOwnerTest {
    @Test
    fun allEvents() = runTest {
        val window = JFrame().apply {
            isVisible = false
        }
        val pane = JLayeredPane().also(window::add)
        var container: ComposeContainer? = null
        SwingUtilities.invokeAndWait {
            container = ComposeContainer(container = pane, skiaLayerAnalytics = SkiaLayerAnalytics.Empty, window = window)
        }
        assertNotNull(container)

        container!!.lifecycle.currentStateFlow.test {
            // initial state for a not-yet-shown window
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.CREATED)

            // show window
            SwingUtilities.invokeLater {
                window.isVisible = true
            }
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.RESUMED)

            // show another window, the window under test looses focus
            val anotherWindow = JFrame().apply {
                isVisible = true
            }
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.STARTED)

            // another window is closed, the window under test regains focus
            anotherWindow.dispose()
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.RESUMED)

            // minimize window
            SwingUtilities.invokeLater {
                window.state = JFrame.ICONIFIED
            }
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.STARTED)
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.CREATED)

            // restore window
            SwingUtilities.invokeLater {
                window.state = JFrame.NORMAL
            }
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.RESUMED)

            // close window
            SwingUtilities.invokeLater {
                container!!.dispose()
            }
            assertThat(awaitItem()).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}