package com.clipmind.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun rememberMovableDialogPosition(): MutableState<MovableDialogPosition> = rememberSaveable(
    stateSaver = listSaver(
        save = { listOf(it.horizontal, it.vertical) },
        restore = { MovableDialogPosition(it[0], it[1]) },
    ),
) { mutableStateOf(MovableDialogPosition()) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MovableDialog(
    title: String,
    position: MovableDialogPosition,
    onPositionChange: (MovableDialogPosition) -> Unit,
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentPosition by rememberUpdatedState(position)
    val updatePosition by rememberUpdatedState(onPositionChange)
    val dismiss by rememberUpdatedState(onDismissRequest)
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val space = IntSize((viewport.width - panel.width).coerceAtLeast(0), (viewport.height - panel.height).coerceAtLeast(0))

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            // The full-window dialog needs its own outside-tap target, behind the panel.
            Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)
                    .onSizeChanged { viewport = it },
            ) {
                Surface(
                    modifier = Modifier.absoluteOffset { currentPosition.offsetWithin(space) }
                        .width((maxWidth - 32.dp).coerceAtLeast(280.dp).coerceAtMost(560.dp).coerceAtMost(maxWidth))
                        .onSizeChanged { panel = it }
                        .semantics { paneTitle = title },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Column(
                            Modifier.fillMaxWidth()
                                .pointerInput(space) {
                                    var dragPosition = currentPosition
                                    detectDragGestures(
                                        onDragStart = { dragPosition = currentPosition },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragPosition = dragPosition.dragBy(delta, space)
                                            updatePosition(dragPosition)
                                        },
                                    )
                                }
                                .semantics(mergeDescendants = true) {
                                    customActions = listOf(CustomAccessibilityAction("将记录框移回屏幕中央") {
                                        updatePosition(MovableDialogPosition())
                                        true
                                    })
                                },
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.align(Alignment.CenterHorizontally).size(32.dp, 4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
                            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                            Text("拖动标题栏移动位置", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            content()
                            Spacer(Modifier.height(24.dp))
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                dismissButton()
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }
}
