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

package androidx.compose.ui.uikit

import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp


@InternalComposeApi
data class SoftwareKeyboardState(
    /**
     * Height that is overlapped with keyboard over Compose view.
     */
    val imeBottomInset: Dp,

    /**
     * Selection Handlers vertical offset when keyboard is visible.
     * Applied when [OnFocusBehavior.FocusableAboveKeyboard] used and
     * [ComposeUIViewControllerConfiguration.platformLayers] are enabled.
     */
    val textSelectionHandlersOffset: Dp
) {
    companion object {
        internal val Initial = SoftwareKeyboardState(
            imeBottomInset = Dp(0f),
            textSelectionHandlersOffset = Dp(0f)
        )
    }
}

/**
 * Composition local for software keyboard state for the current scene.
 */
@InternalComposeApi
val LocalSoftwareKeyboardState = compositionLocalOf { SoftwareKeyboardState.Initial }
