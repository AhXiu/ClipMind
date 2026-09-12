package com.clipmind.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DraggableCaptureButtonTest {
    @Test fun startsAtTheBottomAndCanMoveUpAndDown() {
        assertEquals(400, captureButtonOffset(1f, 400f))
        val raised = dragCaptureButton(1f, -120f, 400f)
        assertEquals(280, captureButtonOffset(raised, 400f))
        assertEquals(330, captureButtonOffset(dragCaptureButton(raised, 50f, 400f), 400f))
    }

    @Test fun staysInsideTheAvailableAreaAtBothEdges() {
        assertEquals(0, captureButtonOffset(dragCaptureButton(0.5f, -2000f, 400f), 400f))
        assertEquals(400, captureButtonOffset(dragCaptureButton(0.5f, 2000f, 400f), 400f))
        assertEquals(390, captureButtonOffset(dragCaptureButton(1f, -10f, 400f), 400f))
    }

    @Test fun fractionalDragEventsAreNotLostToPixelRounding() {
        var fraction = 1f
        repeat(100) { fraction = dragCaptureButton(fraction, -0.1f, 400f) }
        assertEquals(390, captureButtonOffset(fraction, 400f))
    }

    @Test fun keyboardSnackbarAndRotationChangesKeepRelativePosition() {
        val fraction = dragCaptureButton(1f, -100f, 400f)
        assertEquals(300, captureButtonOffset(fraction, 400f))
        assertEquals(75, captureButtonOffset(fraction, 100f))
        assertEquals(600, captureButtonOffset(fraction, 800f))
        assertEquals(300, captureButtonOffset(fraction, 400f))
    }

    @Test fun noAvailableSpaceDoesNotCorruptTheSavedPosition() {
        assertEquals(0, captureButtonOffset(0.75f, 0f))
        assertEquals(0, captureButtonOffset(0.75f, -10f))
        assertEquals(0.75f, dragCaptureButton(0.75f, -10f, 0f), 0f)
        assertEquals(0.75f, dragCaptureButton(0.75f, -10f, -10f), 0f)
    }
}
