package androidx.compose.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.PopupPositionProvider

/**
 * BasicTooltipBox that wraps a composable with a tooltip.
 *
 * Tooltip that provides a descriptive message for an anchor.
 * It can be used to call the users attention to the anchor.
 *
 * @param positionProvider [PopupPositionProvider] that will be used to place the tooltip
 * relative to the anchor content.
 * @param tooltip the composable that will be used to populate the tooltip's content.
 * @param state handles the state of the tooltip's visibility.
 * @param modifier the [Modifier] to be applied to this BasicTooltipBox.
 * @param focusable [Boolean] that determines if the tooltip is focusable. When true,
 * the tooltip will consume touch events while it's shown and will have accessibility
 * focus move to the first element of the component. When false, the tooltip
 * won't consume touch events while it's shown but assistive-tech users will need
 * to swipe or drag to get to the first element of the component.
 * @param enableUserInput [Boolean] which determines if this BasicTooltipBox will handle
 * long press and mouse hover to trigger the tooltip through the state provided.
 * @param content the composable that the tooltip will anchor to.
 */
@ExperimentalFoundationApi
@Composable
actual fun BasicTooltipBox(
    positionProvider: PopupPositionProvider,
    tooltip: @Composable () -> Unit,
    state: BasicTooltipState,
    modifier: Modifier,
    focusable: Boolean,
    enableUserInput: Boolean,
    content: @Composable () -> Unit
) {
}