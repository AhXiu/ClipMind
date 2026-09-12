package com.clipmind.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Fractions preserve placement when the keyboard or window changes the available space. */
internal data class MovableDialogPosition(
    val horizontal: Float = 0.5f,
    val vertical: Float = 0.5f,
) {
    fun offsetWithin(space: IntSize): IntOffset = IntOffset(
        (horizontal.coerceIn(0f, 1f) * space.width.coerceAtLeast(0)).roundToInt(),
        (vertical.coerceIn(0f, 1f) * space.height.coerceAtLeast(0)).roundToInt(),
    )

    fun dragBy(delta: Offset, space: IntSize): MovableDialogPosition = copy(
        horizontal = if (space.width > 0) (horizontal + delta.x / space.width).coerceIn(0f, 1f) else horizontal,
        vertical = if (space.height > 0) (vertical + delta.y / space.height).coerceIn(0f, 1f) else vertical,
    )
}
