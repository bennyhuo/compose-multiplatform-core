package androidx.compose.foundation.gestures

import androidx.compose.animation.SplineBasedFloatDecayAnimationSpec
import androidx.compose.animation.core.generateDecayAnimationSpec

/**
 * This method returns [ScrollableDefaultFlingBehavior] whose density will be managed by the
 * [ScrollableElement] because it's not created inside [Composable] context.
 * This is different from [rememberPlatformDefaultFlingBehavior] which creates [FlingBehavior] whose density
 * depends on [LocalDensity] and is automatically resolved.
 */
internal actual fun platformDefaultFlingBehavior(): ScrollableDefaultFlingBehavior =
    DefaultFlingBehavior(
        SplineBasedFloatDecayAnimationSpec(UnityDensity).generateDecayAnimationSpec()
    )