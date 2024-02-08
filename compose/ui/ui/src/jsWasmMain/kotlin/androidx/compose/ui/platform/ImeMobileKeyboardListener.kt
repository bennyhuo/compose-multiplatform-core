/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.compose.ui.platform

import kotlinx.browser.window
import org.w3c.dom.events.*

internal interface ImeMobileKeyboardListener {
    fun onShow(callback: ()->Unit)
    fun onHide(callback: ()->Unit)
}

internal class ImeKeyboardListenerImpl: ImeMobileKeyboardListener {
    private var callbackOnShow = {}
    private var callbackOnHide = {}

    init {
        val visualViewport = getVisualViewport()

        if (visualViewport != null) {
            val viewportVsClientHeightRatio = 0.75

            visualViewport.addEventListener("resize", { event ->
                val target = event.target as? VisualViewport ?: return@addEventListener
                if (
                    (target.height * target.scale) / window.screen.height <
                    viewportVsClientHeightRatio
                ) {
                    callbackOnShow()
                } else {
                    callbackOnHide()
                }
            }, false)
        }
    }

    override fun onShow(callback: () -> Unit) {
        callbackOnShow = callback
    }

    override fun onHide(callback: () -> Unit) {
        callbackOnHide = callback
    }
}

abstract external class VisualViewport : EventTarget {
    val offsetLeft: Double
    val offsetTop: Double

    val pageLeft: Double
    val pageTop: Double

    val width: Double
    val height: Double

    val scale: Double

    val onresize: (Event) -> Unit
    val onscroll: (Event) -> Unit
    val onscrollend: (Event) -> Unit
}