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

import androidx.compose.runtime.MutableState
import androidx.compose.ui.scene.ComposeSceneMediator
import androidx.compose.ui.uikit.ComposeUIViewControllerConfiguration
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.toDpRect
import kotlin.math.max
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGFloat
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectGetMinY
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSRunLoop
import platform.QuartzCore.CADisplayLink
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCATransactionDisableActions
import platform.UIKit.UIView
import platform.UIKit.UIViewAnimationOptionBeginFromCurrentState
import platform.UIKit.UIViewAnimationOptionCurveEaseInOut
import platform.UIKit.UIViewAnimationOptions
import platform.darwin.NSObject
import platform.darwin.sel_registerName


internal class ComposeContainerKeyboardManager(
    private val configuration: ComposeUIViewControllerConfiguration,
    private val keyboardOverlapHeightState: MutableState<Float>,
    private val viewProvider: () -> UIView,
    private val densityProvider: () -> Density,
    private val composeSceneMediatorProvider: () -> ComposeSceneMediator?
) : KeyboardVisibilityObserver {

    val view get() = viewProvider()

    fun start() {
        KeyboardVisibilityListener.addObserver(this)
        adjustViewBounds(
            KeyboardVisibilityListener.keyboardFrame,
            0.25,
            UIViewAnimationOptionCurveEaseInOut
        )
    }

    fun stop() {
        KeyboardVisibilityListener.removeObserver(this)
    }

    //invisible view to track system keyboard animation
    private val keyboardAnimationView: UIView by lazy {
        UIView(CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
            hidden = true
        }
    }
    private var keyboardAnimationListener: CADisplayLink? = null

    override fun keyboardWillShow(
        targetFrame: CValue<CGRect>,
        duration: Double,
        animationOptions: UIViewAnimationOptions
    ) {
    }

    override fun keyboardWillChangeFrame(
        targetFrame: CValue<CGRect>,
        duration: Double,
        animationOptions: UIViewAnimationOptions
    ) = adjustViewBounds(targetFrame, duration, animationOptions)

    override fun keyboardWillHide(
        targetFrame: CValue<CGRect>,
        duration: Double,
        animationOptions: UIViewAnimationOptions
    ) {
    }

    private fun adjustViewBounds(
        keyboardFrame: CValue<CGRect>, duration: Double, animationOptions: UIViewAnimationOptions
    ) {
        val screen = view.window?.screen ?: return
        val keyboardScreenHeight = if (CGRectIsEmpty(keyboardFrame)) {
            0.0
        } else {
            max(0.0, screen.bounds.useContents { size.height } - CGRectGetMinY(keyboardFrame))
        }

        val imeFrameLeft: Double = if (
            configuration.onFocusBehavior == OnFocusBehavior.FocusableAboveKeyboard
        ) {
            if (keyboardScreenHeight <= 0) {
                // Keyboard is not visible
                updateViewBounds(offsetY = 0.0)
                0.0
            } else {
                val mediator = composeSceneMediatorProvider()
                val focusedRect =
                    mediator?.focusManager?.getFocusRect()?.toDpRect(densityProvider())

                if (focusedRect != null) {
                    val offsetY = calcFocusedLiftingY(mediator, focusedRect, keyboardScreenHeight)
                    updateViewBounds(offsetY = offsetY)
                    keyboardScreenHeight - offsetY
                } else {
                    updateViewBounds(offsetY = 0.0)
                    keyboardScreenHeight
                }
            }
        } else {
            max(keyboardScreenHeight, 0.0)
        }

        val bottomIndent = run {
            val screenHeight = screen.bounds.useContents { size.height }
            val composeViewBottomY = screen.coordinateSpace.convertPoint(
                point = CGPointMake(0.0, view.frame.useContents { size.height }),
                fromCoordinateSpace = view.coordinateSpace
            ).useContents { y }
            screenHeight - composeViewBottomY
        }

        animateKeyboard(imeFrameLeft, bottomIndent, duration, animationOptions)
    }

    private fun animateKeyboard(
        keyboardHeight: CGFloat,
        bottomIndent: CGFloat,
        duration: Double,
        animationOptions: UIViewAnimationOptions
    ) {
        //return actual keyboard height during animation
        fun getCurrentKeyboardHeight(): CGFloat {
            val layer = keyboardAnimationView.layer.presentationLayer() ?: return 0.0
            return layer.frame.useContents { origin.y }
        }

        //attach to root view if needed
        if (keyboardAnimationView.superview == null) {
            view.addSubview(keyboardAnimationView)
        }

        //cancel previous animation
        keyboardAnimationView.layer.removeAllAnimations()
        keyboardAnimationListener?.invalidate()

        //synchronize actual keyboard height with keyboardAnimationView without animation
        val current = getCurrentKeyboardHeight()
        CATransaction.begin()
        CATransaction.setValue(true, kCATransactionDisableActions)
        keyboardAnimationView.setFrame(CGRectMake(0.0, current, 0.0, 0.0))
        CATransaction.commit()

        //animation listener
        keyboardAnimationListener = CADisplayLink.displayLinkWithTarget(
            target = object : NSObject() {
                @OptIn(BetaInteropApi::class)
                @Suppress("unused")
                @ObjCAction
                fun animationDidUpdate() {
                    val currentHeight = getCurrentKeyboardHeight()
                    keyboardOverlapHeightState.value =
                        max(0f, (currentHeight - bottomIndent).toFloat())
                }
            },
            selector = sel_registerName("animationDidUpdate")
        ).apply {
            addToRunLoop(NSRunLoop.mainRunLoop(), NSDefaultRunLoopMode)
        }

        fun completeAnimation() {
            keyboardAnimationListener?.invalidate()
            keyboardAnimationListener = null
            keyboardAnimationView.removeFromSuperview()
            keyboardOverlapHeightState.value = max(0f, (keyboardHeight - bottomIndent).toFloat())
        }

        if (duration > 0 && current != keyboardHeight) {
            UIView.animateWithDuration(
                duration = duration,
                delay = 0.0,
                options = animationOptions or UIViewAnimationOptionBeginFromCurrentState,
                animations = {
                    //set final destination for animation
                    keyboardAnimationView.setFrame(CGRectMake(0.0, keyboardHeight, 0.0, 0.0))
                },
                completion = { isFinished ->
                    if (isFinished) {
                        completeAnimation()
                    }
                }
            )
        } else {
            keyboardAnimationView.setFrame(CGRectMake(0.0, keyboardHeight, 0.0, 0.0))
            completeAnimation()
        }
    }

    private fun calcFocusedLiftingY(
        composeSceneMediator: ComposeSceneMediator,
        focusedRect: DpRect,
        keyboardHeight: Double
    ): Double {
        val viewHeight = composeSceneMediator.getViewHeight()
        val hiddenPartOfFocusedElement: Double =
            keyboardHeight - viewHeight + focusedRect.bottom.value
        return if (hiddenPartOfFocusedElement > 0) {
            // If focused element is partially hidden by the keyboard, we need to lift it upper
            val focusedTopY = focusedRect.top.value
            val isFocusedElementRemainsVisible = hiddenPartOfFocusedElement < focusedTopY
            if (isFocusedElementRemainsVisible) {
                // We need to lift focused element to be fully visible
                hiddenPartOfFocusedElement
            } else {
                // In this case focused element height is bigger than remain part of the screen after showing the keyboard.
                // Top edge of focused element should be visible. Same logic on Android.
                maxOf(focusedTopY, 0f).toDouble()
            }
        } else {
            // Focused element is not hidden by the keyboard.
            0.0
        }
    }

    private fun updateViewBounds(offsetX: Double = 0.0, offsetY: Double = 0.0) {
        view.layer.setBounds(
            view.frame.useContents {
                CGRectMake(
                    x = offsetX,
                    y = offsetY,
                    width = size.width,
                    height = size.height
                )
            }
        )
    }
}
