package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.calendar.CalendarEngine
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Financial calendar for the browsed month: confirmed money movement per day,
 * plus a pending counter for unreviewed detections — pending items are shown
 * as a count only, never as money.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSheet(
    smartState: SmartLedgerUiState,
    currencySymbol: String,
    month: YearMonth,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    val summary = smartState.calendarSummary
    var selectedDay by remember(month) { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("FINANCIAL CALENDAR", style = MaterialTheme.typography.labelSmall, color = colors.ochreAccent)
            Spacer(Modifier.height(4.dp))
            Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(12.dp))

            // ---- week & month summaries
            if (summary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryCard(
                        title = "THIS WEEK (7 DAYS)",
                        incoming = summary.weekIncomingCents,
                        outgoing = summary.weekOutgoingCents,
                        count = summary.weekCount,
                        currencySymbol = currencySymbol,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        title = "THIS MONTH",
                        incoming = summary.monthIncomingCents,
                        outgoing = summary.monthOutgoingCents,
                        count = summary.monthCount,
                        currencySymbol = currencySymbol,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val pendingInMonth = smartState.pendingDetections.count {
                LocalDate.ofEpochDay(it.timestampEpochMillis / 86_400_000L).let { d ->
                    YearMonth.from(d) == month
                }
            }
            if (pendingInMonth > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "● $pendingInMonth unreviewed detection(s) in this month — counted, not money.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warningAccent
                )
            }

            Spacer(Modifier.height(14.dp))

            // ---- weekday header
            val weekdayLabels = listOf("M", "T", "W", "T", "F", "S", "S")
            Row(Modifier.fillMaxWidth()) {
                weekdayLabels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- day grid
            val daysInMonth = CalendarEngine.daysInMonth(month.year, month.monthValue)
            val leading = CalendarEngine.firstWeekdayIndex(month.year, month.monthValue)
            val cells = mutableListOf<LocalDate?>()
            repeat(leading) { cells.add(null) }
            for (day in 1..daysInMonth) cells.add(month.atDay(day))
            while (cells.size % 7 != 0) cells.add(null)

            cells.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    week.forEach { date ->
                        Box(modifier = Modifier.weight(1f).padding(1.dp)) {
                            if (date != null) {
                                val info = summary?.days?.get(date.toEpochDay())
                                val hasIn = (info?.incomingCents ?: 0L) > 0L
                                val hasOut = (info?.outgoingCents ?: 0L) > 0L
                                val isToday = date == today
                                val isSelected = date == selectedDay
                                val cellColors = when {
                                    hasIn && hasOut -> colors.ochreAccent.copy(alpha = 0.18f)
                                    hasIn -> colors.positiveAccent.copy(alpha = 0.18f)
                                    hasOut -> colors.alertAccent.copy(alpha = 0.14f)
                                    else -> colors.elevatedSurface
                                }
                                val textColor = when {
                                    isSelected -> colors.surface
                                    hasIn && !hasOut -> colors.positiveAccent
                                    hasOut -> colors.alertAccent
                                    else -> colors.inkSecondary
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(cellColors)
                                        .border(
                                            width = if (isToday) 1.dp else 0.dp,
                                            color = if (isSelected) colors.inkPrimary else colors.fjordAccent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            selectedDay = if (selectedDay == date) null else date
                                        }
                                        .padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = date.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textColor
                                    )
                                    if (info != null && info.transactionCount > 0) {
                                        Text(
                                            text = info.transactionCount.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = textColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- selected day detail
            selectedDay?.let { day ->
                Spacer(Modifier.height(10.dp))
                val info = summary?.days?.get(day.toEpochDay())
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.elevatedSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(day.format(formatter), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                        Spacer(Modifier.height(4.dp))
                        if (info == null || info.transactionCount == 0) {
                            val pending = info?.pendingCount ?: 0
                            Text(
                                if (pending > 0) "No confirmed activity · $pending pending detection(s)"
                                else "No activity recorded on this day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkMuted
                            )
                        } else {
                            Text(
                                "In ${CurrencyFormatter.formatCents(info.incomingCents, currencySymbol)} · " +
                                    "Out ${CurrencyFormatter.formatCents(info.outgoingCents, currencySymbol)} · " +
                                    "Net ${CurrencyFormatter.formatCents(info.netCents, currencySymbol, showPlusForPositive = true)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkPrimary
                            )
                            Text(
                                "${info.transactionCount} confirmed transaction(s)" +
                                    (if (info.pendingCount > 0) " · ${info.pendingCount} pending (not money yet)" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (info.pendingCount > 0) colors.warningAccent else colors.inkMuted
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                "Calendar totals count confirmed activity only. Upcoming recurring amounts are " +
                    "forecasts and appear under Recurring — never here as spent money.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(bottom = 20.dp)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    incoming: Long,
    outgoing: Long,
    count: Int,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.elevatedSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Text(
                CurrencyFormatter.formatCents(incoming - outgoing, currencySymbol, showPlusForPositive = true),
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary
            )
            Text(
                "in ${CurrencyFormatter.formatCentsCompact(incoming, currencySymbol)} · " +
                    "out ${CurrencyFormatter.formatCentsCompact(outgoing, currencySymbol)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary
            )
            Text("$count transactions", style = MaterialTheme.typography.bodySmall, color = colors.inkMuted)
        }
    }
}
