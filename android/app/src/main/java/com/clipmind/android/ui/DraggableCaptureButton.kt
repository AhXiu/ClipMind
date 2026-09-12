package com.clipmind.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal fun captureButtonOffset(fraction: Float, travel: Float): Int =
    (fraction.coerceIn(0f, 1f) * travel.coerceAtLeast(0f)).roundToInt()

internal fun dragCaptureButton(fraction: Float, deltaY: Float, travel: Float): Float =
    if (travel > 0f) (fraction + deltaY / travel).coerceIn(0f, 1f) else fraction

@Composable
internal fun DraggableCaptureButton(
    fraction: Float,
    onPositionChange: (Float) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentFraction by rememberUpdatedState(fraction)
    val updatePosition by rememberUpdatedState(onPositionChange)
    val diameter = 56.dp
    var buttonHeight by remember(density) { mutableIntStateOf(with(density) { diameter.roundToPx() }) }
    val step = with(density) { diameter.toPx() }
    val shape = CircleShape
    val foreground = MaterialTheme.colorScheme.onPrimary

    BoxWithConstraints(modifier) {
        val travel = (with(density) { maxHeight.toPx() } - buttonHeight).coerceAtLeast(0f)
        if (maxHeight >= diameter && maxWidth >= diameter) {
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, captureButtonOffset(currentFraction, travel)) }
                    .size(diameter)
                    .border(1.dp, foreground.copy(alpha = 0.14f), shape)
                    .onSizeChanged { buttonHeight = it.height }
                    .pointerInput(travel) {
                        var dragFraction = currentFraction
                        // Foundation waits for touch slop and consumes the drag, cancelling the button click.
                        detectVerticalDragGestures(
                            onDragStart = { dragFraction = currentFraction },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                dragFraction = dragCaptureButton(dragFraction, delta, travel)
                                updatePosition(dragFraction)
                            },
                        )
                    }
                    .semantics {
                        contentDescription = "记录一张卡片，点击记录，上下拖动调整位置"
                        customActions = listOf(
                            CustomAccessibilityAction("上移记录按钮") {
                                updatePosition(dragCaptureButton(currentFraction, -step, travel)); true
                            },
                            CustomAccessibilityAction("下移记录按钮") {
                                updatePosition(dragCaptureButton(currentFraction, step, travel)); true
                            },
                            CustomAccessibilityAction("恢复记录按钮默认位置") { updatePosition(1f); true },
                        )
                    },
                shape = shape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = foreground,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
            ) {
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    val inset = 3.dp.toPx()
                    drawLine(foreground, Offset(center.x, inset), Offset(center.x, size.height - inset), stroke, StrokeCap.Round)
                    drawLine(foreground, Offset(inset, center.y), Offset(size.width - inset, center.y), stroke, StrokeCap.Round)
                }
            }
        }
    }
}
