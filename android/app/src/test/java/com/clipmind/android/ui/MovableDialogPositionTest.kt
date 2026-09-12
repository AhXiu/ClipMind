package com.clipmind.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class MovableDialogPositionTest {
    @Test fun opensCentered() {
        assertEquals(IntOffset(100, 200), MovableDialogPosition().offsetWithin(IntSize(200, 400)))
    }

    @Test fun followsDragOnBothAxes() {
        val space = IntSize(200, 400)
        val moved = MovableDialogPosition().dragBy(Offset(30f, -80f), space)
        assertEquals(IntOffset(130, 120), moved.offsetWithin(space))
    }

    @Test fun smallDragEventsAccumulateWithoutPixelRoundingLoss() {
        val space = IntSize(200, 400)
        var position = MovableDialogPosition()
        repeat(50) { position = position.dragBy(Offset(0.2f, -0.2f), space) }
        assertEquals(IntOffset(110, 190), position.offsetWithin(space))
    }

    @Test fun draggingPastAnyEdgeKeepsThePanelVisible() {
        val space = IntSize(200, 400)
        assertEquals(IntOffset(0, 0), MovableDialogPosition().dragBy(Offset(-1000f, -1000f), space).offsetWithin(space))
        assertEquals(IntOffset(200, 400), MovableDialogPosition().dragBy(Offset(1000f, 1000f), space).offsetWithin(space))
    }

    @Test fun draggingBackFromAnEdgeMovesImmediately() {
        val space = IntSize(200, 400)
        val edge = MovableDialogPosition().dragBy(Offset(1000f, 1000f), space)
        assertEquals(IntOffset(190, 380), edge.dragBy(Offset(-10f, -20f), space).offsetWithin(space))
    }

    @Test fun keyboardAndRotationResizeWithoutLosingRelativePlacement() {
        val position = MovableDialogPosition(0.25f, 0.75f)
        assertEquals(IntOffset(50, 300), position.offsetWithin(IntSize(200, 400)))
        assertEquals(IntOffset(100, 75), position.offsetWithin(IntSize(400, 100)))
        assertEquals(IntOffset(50, 300), position.offsetWithin(IntSize(200, 400)))
    }

    @Test fun aPanelFillingTheViewportStaysVisibleAndKeepsItsSavedPosition() {
        val position = MovableDialogPosition(0.25f, 0.75f)
        assertEquals(IntOffset.Zero, position.offsetWithin(IntSize.Zero))
        assertEquals(position, position.dragBy(Offset(100f, -200f), IntSize.Zero))
        assertEquals(IntOffset.Zero, position.offsetWithin(IntSize(-100, -200)))
    }

    @Test fun fullWidthPanelCanStillMoveVertically() {
        val space = IntSize(0, 400)
        val position = MovableDialogPosition().dragBy(Offset(100f, -100f), space)
        assertEquals(IntOffset(0, 100), position.offsetWithin(space))
        assertEquals(0.5f, position.horizontal, 0f)
    }
}
