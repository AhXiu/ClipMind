package com.clipmind.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF17211E)
private val Pine = Color(0xFF176B57)
private val Mist = Color(0xFFE3F1EC)
private val Paper = Color(0xFFF7F8F4)
private val Line = Color(0xFFDDE3DE)
private val Amber = Color(0xFF9A5B13)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    primaryContainer = Mist,
    onPrimaryContainer = Color(0xFF0B493A),
    secondary = Color(0xFF52655E),
    secondaryContainer = Color(0xFFE8EEEA),
    onSecondaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFCFDF9),
    onSurface = Ink,
    surfaceVariant = Color(0xFFF0F3EF),
    onSurfaceVariant = Color(0xFF5D6863),
    outline = Color(0xFF8A9690),
    outlineVariant = Line,
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
)

private val ClipMindTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ClipMindTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = ClipMindTypography,
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        action?.invoke(this)
    }
}

@Composable
fun StatusPill(label: String, positive: Boolean = false, warning: Boolean = false) {
    val background = when {
        positive -> MaterialTheme.colorScheme.primaryContainer
        warning -> if (MaterialTheme.colorScheme.background.luminance() > .5f) Color(0xFFFFE9CC) else Color(0xFF5A3B16)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        positive -> MaterialTheme.colorScheme.onPrimaryContainer
        warning -> if (MaterialTheme.colorScheme.background.luminance() > .5f) Amber else Color(0xFFFFD39A)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = background, contentColor = foreground, shape = CircleShape) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FlatCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun TabGlyph(tab: AppTab, selected: Boolean, modifier: Modifier = Modifier) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
        when (tab) {
            AppTab.CAPTURE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * .24f, size.height * .19f),
                    size = Size(size.width * .52f, size.height * .66f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke,
                )
                drawLine(color, Offset(size.width * .39f, size.height * .14f), Offset(size.width * .61f, size.height * .14f), 3.dp.toPx(), StrokeCap.Round)
                drawLine(color, Offset(size.width * .36f, size.height * .43f), Offset(size.width * .64f, size.height * .43f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .36f, size.height * .60f), Offset(size.width * .56f, size.height * .60f), stroke.width, StrokeCap.Round)
            }
            AppTab.LIBRARY -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * .17f, size.height * .24f),
                    size = Size(size.width * .66f, size.height * .60f),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = stroke,
                )
                drawLine(color, Offset(size.width * .25f, size.height * .16f), Offset(size.width * .75f, size.height * .16f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .31f, size.height * .48f), Offset(size.width * .69f, size.height * .48f), stroke.width, StrokeCap.Round)
            }
            AppTab.AI -> {
                drawCircle(color = color, radius = size.width * .27f, center = center, style = stroke)
                drawLine(color, Offset(size.width * .50f, size.height * .10f), Offset(size.width * .50f, size.height * .23f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .50f, size.height * .77f), Offset(size.width * .50f, size.height * .90f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .10f, size.height * .50f), Offset(size.width * .23f, size.height * .50f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .77f, size.height * .50f), Offset(size.width * .90f, size.height * .50f), stroke.width, StrokeCap.Round)
            }
            AppTab.SETTINGS -> {
                drawCircle(color = color, radius = size.width * .31f, center = center, style = stroke)
                drawCircle(color = color, radius = size.width * .09f, center = center)
                repeat(4) { index ->
                    val horizontal = index % 2 == 0
                    val start = if (horizontal) Offset(if (index == 0) size.width * .14f else size.width * .72f, center.y) else Offset(center.x, if (index == 1) size.height * .14f else size.height * .72f)
                    val end = if (horizontal) Offset(if (index == 0) size.width * .28f else size.width * .86f, center.y) else Offset(center.x, if (index == 1) size.height * .28f else size.height * .86f)
                    drawLine(color, start, end, stroke.width, StrokeCap.Round)
                }
            }
        }
    }
}
