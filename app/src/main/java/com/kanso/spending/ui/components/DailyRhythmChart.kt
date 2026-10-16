package com.kanso.spending.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.DailyBarPoint
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

@Composable
fun DailyRhythmChart(
    dailyBars: List<DailyBarPoint>,
    dailyTargetCents: Long,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current
    var inspectedIndex by remember(dailyBars) {
        mutableStateOf(dailyBars.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: dailyBars.lastIndex.coerceAtLeast(0))
    }
    val inspectedBar = dailyBars.getOrNull(inspectedIndex)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("DAILY SPEND RHYTHM", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                Spacer(Modifier.height(2.dp))
                val subtitle = inspectedBar?.let {
                    "${it.date.format(shortDateFormatter).uppercase(Locale.US)} • ${CurrencyFormatter.formatCents(it.spentCents, currencySymbol)}"
                } ?: "Select a bar to inspect its daily total"
                Text(subtitle, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("DAILY TARGET", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                Text(CurrencyFormatter.formatCentsCompact(dailyTargetCents, currencySymbol), style = MaterialTheme.typography.labelLarge, color = colors.positiveAccent)
            }
        }

        Spacer(Modifier.height(14.dp))

        val maxSpend = remember(dailyBars, dailyTargetCents) {
            maxOf(dailyBars.maxOfOrNull { it.spentCents } ?: 0L, dailyTargetCents * 2L, 5_000L)
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(98.dp)
                .pointerInput(dailyBars) {
                    detectTapGestures { offset ->
                        if (dailyBars.isNotEmpty() && size.width > 0) {
                            inspectedIndex = ((offset.x / size.width) * dailyBars.size).toInt().coerceIn(0, dailyBars.lastIndex)
                        }
                    }
                }
        ) {
            if (dailyBars.isEmpty()) return@Canvas
            val slotW = size.width / dailyBars.size.toFloat()
            val barW = (slotW * 0.62f).coerceAtLeast(4f)
            val targetRatio = (dailyTargetCents.toFloat() / maxSpend.toFloat()).coerceIn(0.08f, 0.92f)
            val targetY = size.height * (1f - targetRatio)

            drawLine(
                color = colors.positiveAccent.copy(alpha = 0.55f),
                start = Offset(0f, targetY),
                end = Offset(size.width, targetY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            )

            dailyBars.forEachIndexed { idx, bar ->
                val x = idx * slotW + (slotW - barW) / 2f
                val ratio = if (bar.spentCents > 0L) (bar.spentCents.toFloat() / maxSpend.toFloat()).coerceIn(0.06f, 1f) else 0.03f
                val h = size.height * ratio
                val c = when {
                    idx == inspectedIndex -> colors.inkPrimary
                    bar.spentCents > dailyTargetCents && dailyTargetCents > 0L -> colors.alertAccent.copy(alpha = 0.82f)
                    bar.spentCents > 0L -> colors.positiveAccent.copy(alpha = 0.72f)
                    else -> colors.hairlineBorder
                }
                drawRoundRect(c, Offset(x, size.height - h), Size(barW, h), CornerRadius(4f, 4f))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("DAY 01", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Text("MID-MONTH", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Text("DAY ${dailyBars.size.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        }
    }
}
