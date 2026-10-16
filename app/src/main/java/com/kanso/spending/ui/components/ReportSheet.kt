package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kanso.spending.domain.LedgerReportPeriod
import com.kanso.spending.ui.ReportFormat
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val PERIOD_LABELS = mapOf(
    LedgerReportPeriod.TODAY to "Today",
    LedgerReportPeriod.YESTERDAY to "Yesterday",
    LedgerReportPeriod.LAST_7_DAYS to "Last 7 Days",
    LedgerReportPeriod.THIS_WEEK to "This Week",
    LedgerReportPeriod.LAST_WEEK to "Last Week",
    LedgerReportPeriod.THIS_MONTH to "This Month",
    LedgerReportPeriod.LAST_MONTH to "Last Month",
    LedgerReportPeriod.CUSTOM to "Custom Range"
)

/**
 * Download Report sheet: pick a date range, preview nothing extra, and export a
 * print-friendly PDF or a spreadsheet-friendly CSV. Generation is 100% local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    uiState: SpendingDashboardUiState,
    onExport: (period: LedgerReportPeriod, start: LocalDate?, end: LocalDate?, format: ReportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    var period by remember { mutableStateOf(LedgerReportPeriod.THIS_MONTH) }
    var customStart by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var customEnd by remember { mutableStateOf(LocalDate.now()) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("EXPENSE REPORT", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Text("Download Report", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Every report states its range, opening & closing balance, money added, expenses, category and payment-method breakdowns, and line-by-line transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted
            )
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PERIOD_LABELS.forEach { (p, label) ->
                    val active = period == p
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { period = p }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                    }
                }
            }

            if (period == LedgerReportPeriod.CUSTOM) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("$customStart → $customEnd", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    Row {
                        TextButton(onClick = { pickStart = true }) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("START", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { pickEnd = true }) {
                            Text("END", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onExport(period, customStart, customEnd, ReportFormat.PDF) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
            ) {
                Text("DOWNLOAD PDF REPORT", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onExport(period, customStart, customEnd, ReportFormat.CSV) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("EXPORT CSV FOR SPREADSHEETS", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = "Reports are generated entirely on-device with the ৳ symbol and saved to a location you choose. No network is used.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
        }
    }

    if (pickStart) {
        DatePickerDialog(
            onDismissRequest = { pickStart = false },
            confirmButton = { TextButton(onClick = { pickStart = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickStart = false }) { Text("Cancel") } }
        ) {
            val state = rememberDatePickerState(initialSelectedDateMillis = toMillis(customStart))
            LaunchedEffect(state.selectedDateMillis) {
                state.selectedDateMillis?.let { customStart = toLocalDate(it) }
            }
            DatePicker(state = state)
        }
    }

    if (pickEnd) {
        DatePickerDialog(
            onDismissRequest = { pickEnd = false },
            confirmButton = { TextButton(onClick = { pickEnd = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickEnd = false }) { Text("Cancel") } }
        ) {
            val state = rememberDatePickerState(initialSelectedDateMillis = toMillis(customEnd))
            LaunchedEffect(state.selectedDateMillis) {
                state.selectedDateMillis?.let { customEnd = toLocalDate(it) }
            }
            DatePicker(state = state)
        }
    }
}

private fun toMillis(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun toLocalDate(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
