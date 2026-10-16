package com.kanso.spending.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SouthWest
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.LedgerReportPeriod
import com.kanso.spending.domain.MonthlyLedgerAnalytics
import com.kanso.spending.ui.LedgerTypeFilter
import com.kanso.spending.ui.ReportFormat
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.components.*
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.LocalDate

@Composable
fun SpendingDashboardScreen(
    uiState: SpendingDashboardUiState,
    smartState: SmartLedgerUiState,
    smartActions: SmartLedgerActions,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectCategoryFilter: (String?) -> Unit,
    onSelectMethodFilter: (String?) -> Unit,
    onTypeFilterChange: (LedgerTypeFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleTheme: () -> Unit,
    onSaveExpense: (Long?, String, Long, String, PaymentMethod, String, Long, String, Boolean) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
    onDeleteIncome: (com.kanso.spending.data.local.entity.IncomeEntity) -> Unit,
    onSaveMoneyIn: (Long?, String, Long, com.kanso.spending.data.model.MoneySource, PaymentMethod, String, Long, String) -> Unit,
    onCreateCategory: (String, String, Long) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String, String?) -> Unit,
    onSaveBudgetConfig: (Long, String, Map<String, Long>) -> Unit,
    onPrepareReport: (LedgerReportPeriod, LocalDate?, LocalDate?, ReportFormat) -> Unit,
    onClearPendingReport: () -> Unit,
    onGenerateBackupJson: () -> Unit,
    onImportBackupJson: (String) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onClearVaultMessage: () -> Unit
) {
    val colors = LocalKansoColors.current
    val context = LocalContext.current
    var showExpenseSheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showBudgetSheet by remember { mutableStateOf(false) }
    var showVaultSheet by remember { mutableStateOf(false) }
    var showMoneyInSheet by remember { mutableStateOf(false) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showReportSheet by remember { mutableStateOf(false) }
    var showSmartSmsSheet by remember { mutableStateOf(false) }
    var showAccountsSheet by remember { mutableStateOf(false) }
    var showCalendarSheet by remember { mutableStateOf(false) }
    var showRecurringSheet by remember { mutableStateOf(false) }
    var showGoalsSheet by remember { mutableStateOf(false) }
    var showQuickEntrySheet by remember { mutableStateOf(false) }
    var showSmartSettingsSheet by remember { mutableStateOf(false) }

    // ---------------------------------------------------- SAF report saving
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val report = uiState.pendingReport
        if (uri != null && report != null && report.format == ReportFormat.PDF) {
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(report.bytes) }
        }
        onClearPendingReport()
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val report = uiState.pendingReport
        if (uri != null && report != null && report.format == ReportFormat.CSV) {
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(report.bytes) }
        }
        onClearPendingReport()
    }

    // When a report is prepared, immediately ask the user where to save it.
    var launchedReportName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.pendingReport) {
        val report = uiState.pendingReport ?: return@LaunchedEffect
        if (launchedReportName != report.suggestedFileName) {
            launchedReportName = report.suggestedFileName
            if (report.format == ReportFormat.PDF) {
                pdfLauncher.launch(report.suggestedFileName)
            } else {
                csvLauncher.launch(report.suggestedFileName)
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editingExpense = null
                    showExpenseSheet = true
                },
                containerColor = colors.inkPrimary,
                contentColor = colors.surface,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Log Expense")
                Spacer(Modifier.width(8.dp))
                Text("RECORD EXPENSE", style = MaterialTheme.typography.labelLarge)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            item {
                EditorialHeaderBar(
                    selectedMonth = uiState.selectedMonth,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onOpenBudgetPlanner = { showBudgetSheet = true },
                    onOpenLocalVault = { showVaultSheet = true },
                    onToggleTheme = onToggleTheme
                )
            }

            item {
                BalanceStripCard(uiState = uiState)
                Spacer(Modifier.height(14.dp))
            }

            item {
                DashboardActionRow(
                    onAddMoney = { showMoneyInSheet = true },
                    onRecordExpense = {
                        editingExpense = null
                        showExpenseSheet = true
                    },
                    onManageCategories = { showCategorySheet = true },
                    onDownloadReport = { showReportSheet = true }
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                IntelligenceSection(
                    smartState = smartState,
                    currencySymbol = uiState.currencySymbol,
                    onOpenReview = { showSmartSmsSheet = true },
                    onOpenAccounts = { showAccountsSheet = true }
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                SmartActionRow(
                    smartState = smartState,
                    onOpenSms = { showSmartSmsSheet = true },
                    onOpenAccounts = { showAccountsSheet = true },
                    onOpenCalendar = { showCalendarSheet = true },
                    onOpenRecurring = { showRecurringSheet = true },
                    onOpenGoals = { showGoalsSheet = true },
                    onOpenQuickEntry = { showQuickEntrySheet = true },
                    onOpenSettings = { showSmartSettingsSheet = true }
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                HeroLedgerCard(
                    analytics = uiState.analytics,
                    currencySymbol = uiState.currencySymbol,
                    selectedCategory = uiState.selectedCategoryFilter,
                    onSelectCategory = onSelectCategoryFilter,
                    onEditBudgetClick = { showBudgetSheet = true }
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                val dailyTarget = (uiState.analytics.monthlyBudgetCents / uiState.selectedMonth.lengthOfMonth().coerceAtLeast(1)).coerceAtLeast(100L)
                DailyRhythmChart(
                    dailyBars = uiState.analytics.dailyBars,
                    dailyTargetCents = dailyTarget,
                    currencySymbol = uiState.currencySymbol
                )
                Spacer(Modifier.height(14.dp))
            }

            item {
                LedgerFilterAndSearchBar(
                    uiState = uiState,
                    onSearchQueryChange = onSearchQueryChange,
                    onSelectCategory = onSelectCategoryFilter,
                    onSelectMethod = onSelectMethodFilter,
                    onTypeFilterChange = onTypeFilterChange
                )
            }

            item {
                PaymentMethodSpendCard(
                    analytics = uiState.analytics,
                    currencySymbol = uiState.currencySymbol,
                    selectedMethod = uiState.selectedMethodFilter,
                    onSelectMethod = onSelectMethodFilter
                )
                Spacer(Modifier.height(6.dp))
            }

            if (uiState.analytics.daySections.isEmpty()) {
                item {
                    EmptyLedgerNotice(
                        hasActiveFilter = uiState.selectedCategoryFilter != null ||
                            uiState.selectedMethodFilter != null ||
                            uiState.typeFilter != LedgerTypeFilter.ALL ||
                            uiState.searchQuery.isNotBlank(),
                        onClearFilters = {
                            onSelectCategoryFilter(null)
                            onSelectMethodFilter(null)
                            onTypeFilterChange(LedgerTypeFilter.ALL)
                            onSearchQueryChange("")
                        }
                    )
                }
            } else {
                items(uiState.analytics.daySections, key = { it.date.toEpochDay() }) { section ->
                    DayLedgerSectionCard(
                        section = section,
                        currencySymbol = uiState.currencySymbol,
                        categoryDisplay = { id ->
                            (uiState.categoryList.firstOrNull { it.id == id })?.let {
                                com.kanso.spending.domain.CategoryDisplayInfo(it.id, it.displayName, it.colorHex)
                            } ?: com.kanso.spending.domain.CategoryDisplayInfo(id, id, 0xFF161614)
                        },
                        onEditExpense = { exp ->
                            editingExpense = exp
                            showExpenseSheet = true
                        },
                        onDeleteExpense = onDeleteExpense,
                        onDeleteIncome = onDeleteIncome
                    )
                }
            }
        }
    }

    if (showExpenseSheet) {
        TactileExpenseSheet(
            editingExpense = editingExpense,
            categories = uiState.categoryList,
            currencySymbol = uiState.currencySymbol,
            merchantSuggestions = uiState.analytics.merchantSuggestions.map { it.title },
            onSaveExpense = onSaveExpense,
            onDismiss = {
                showExpenseSheet = false
                editingExpense = null
            }
        )
    }

    if (showMoneyInSheet) {
        MoneyInSheet(
            uiState = uiState,
            onSaveMoneyIn = onSaveMoneyIn,
            onDismiss = { showMoneyInSheet = false }
        )
    }

    if (showCategorySheet) {
        CategoryManagerSheet(
            uiState = uiState,
            onCreateCategory = onCreateCategory,
            onRenameCategory = onRenameCategory,
            onDeleteCategory = onDeleteCategory,
            onDismiss = { showCategorySheet = false }
        )
    }

    if (showReportSheet) {
        ReportSheet(
            uiState = uiState,
            onExport = { period, start, end, format ->
                onPrepareReport(period, start, end, format)
                showReportSheet = false
            },
            onDismiss = { showReportSheet = false }
        )
    }

    if (showBudgetSheet) {
        BudgetPlannerSheet(
            currentMonthlyBudgetCents = uiState.analytics.monthlyBudgetCents,
            categoryBreakdown = uiState.analytics.categoryBreakdown,
            categories = uiState.categoryList,
            currencySymbol = uiState.currencySymbol,
            onSaveConfiguration = onSaveBudgetConfig,
            onDismiss = { showBudgetSheet = false }
        )
    }

    if (showVaultSheet) {
        LocalVaultSheet(
            backupJsonPreview = uiState.backupJsonPreview,
            statusMessage = uiState.vaultStatusMessage,
            auditLines = uiState.auditLines,
            onGenerateBackupJson = onGenerateBackupJson,
            onImportBackupJson = onImportBackupJson,
            onWipeAllLocalData = onWipeAllLocalData,
            onDismiss = {
                onClearVaultMessage()
                showVaultSheet = false
            }
        )
    }

    if (showSmartSmsSheet) {
        SmartSmsSheet(
            uiState = uiState,
            smartState = smartState,
            categories = uiState.categoryList,
            actions = smartActions,
            onDismiss = {
                showSmartSmsSheet = false
                smartActions.onClearSmartStatus()
            }
        )
    }

    if (showAccountsSheet) {
        AccountsSheet(
            smartState = smartState,
            currencySymbol = uiState.currencySymbol,
            actions = smartActions,
            onDismiss = {
                showAccountsSheet = false
                smartActions.onClearSmartStatus()
            }
        )
    }

    if (showCalendarSheet) {
        CalendarSheet(
            smartState = smartState,
            currencySymbol = uiState.currencySymbol,
            month = uiState.selectedMonth,
            onDismiss = { showCalendarSheet = false }
        )
    }

    if (showRecurringSheet) {
        RecurringSheet(
            smartState = smartState,
            categories = uiState.categoryList,
            currencySymbol = uiState.currencySymbol,
            actions = smartActions,
            onDismiss = {
                showRecurringSheet = false
                smartActions.onClearSmartStatus()
            }
        )
    }

    if (showGoalsSheet) {
        GoalsSheet(
            smartState = smartState,
            currencySymbol = uiState.currencySymbol,
            actions = smartActions,
            onDismiss = {
                showGoalsSheet = false
                smartActions.onClearSmartStatus()
            }
        )
    }

    if (showQuickEntrySheet) {
        QuickEntrySheet(
            uiState = uiState,
            categories = uiState.categoryList,
            onSaveExpense = onSaveExpense,
            onDismiss = { showQuickEntrySheet = false }
        )
    }

    if (showSmartSettingsSheet) {
        SmartSettingsSheet(
            smartState = smartState,
            actions = smartActions,
            onDismiss = {
                showSmartSettingsSheet = false
                smartActions.onClearSmartStatus()
            }
        )
    }
}

@Composable
private fun DashboardActionRow(
    onAddMoney: () -> Unit,
    onRecordExpense: () -> Unit,
    onManageCategories: () -> Unit,
    onDownloadReport: () -> Unit
) {
    val colors = LocalKansoColors.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("LEDGER ACTIONS", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton("ADD MONEY", Icons.Outlined.SouthWest, colors.positiveAccent, onAddMoney)
            ActionButton("MANAGE CATEGORIES", Icons.Outlined.Category, colors.fjordAccent, onManageCategories)
            ActionButton("DOWNLOAD REPORT", Icons.Outlined.Download, colors.ochreAccent, onDownloadReport)
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) {
    val colors = LocalKansoColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
    }
}

/**
 * Net funds across accounts plus the two honesty signals: unreviewed
 * detections (counted, not money) and provider reconciliation warnings
 * (never silently applied). Followed by data-only observations.
 */
@Composable
private fun IntelligenceSection(
    smartState: SmartLedgerUiState,
    currencySymbol: String,
    onOpenReview: () -> Unit,
    onOpenAccounts: () -> Unit
) {
    val colors = LocalKansoColors.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.hairlineBorder)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("NET FUNDS • ALL ACCOUNTS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    if (smartState.pendingDetections.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.warningAccent.copy(alpha = 0.16f))
                                .clickable(onClick = onOpenReview)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "${smartState.pendingDetections.size} TO REVIEW",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.warningAccent,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    com.kanso.spending.domain.CurrencyFormatter.formatCents(smartState.netWorthCents, currencySymbol),
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (smartState.netWorthCents < 0L) colors.alertAccent else colors.inkPrimary
                )

                if (smartState.accountStates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        smartState.accountStates.filter { !it.isArchived }.forEach { state ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.background)
                                    .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpenAccounts)
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    "${state.name} ${com.kanso.spending.domain.CurrencyFormatter.formatCentsCompact(state.calculatedBalanceCents, currencySymbol)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.inkSecondary
                                )
                            }
                        }
                    }
                }

                if (smartState.reconciliationWarningCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.warningAccent.copy(alpha = 0.12f))
                            .clickable(onClick = onOpenAccounts)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚠ ${smartState.reconciliationWarningCount} account(s) differ from provider reports — " +
                                "tap to review. Your ledger values were not overwritten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warningAccent
                        )
                    }
                }

                // ---- smart tracking status: enabled, mode, last run, pending
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.background)
                        .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenReview)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        val smsOn = smartState.smartSettings.smsEnabled
                        Text(
                            if (smsOn) "SMART TRACKING: ON" else "SMART TRACKING: OFF — MANUAL ENTRY UNAFFECTED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (smsOn) colors.positiveAccent else colors.inkMuted,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        val modeLabel =
                            if (smartState.smartSettings.smsImportMode == com.kanso.spending.domain.sms.SmsImportMode.AUTO) {
                                "auto-record confident"
                            } else {
                                "review first"
                            }
                        val lastRun = smartState.lastIngestSummary
                            ?.let { "last run: ${it.recorded} recorded, ${it.queuedForReview} queued" }
                            ?: "no successful processing yet"
                        Text(
                            "Mode: $modeLabel · $lastRun · ${smartState.pendingDetections.size} pending",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary
                        )
                    }
                    Text(
                        "MANAGE",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.fjordAccent
                    )
                }
            }
        }

        // ---- data-only observations
        val visibleInsights = smartState.insights.take(3)
        if (visibleInsights.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "OBSERVATIONS • FROM YOUR LEDGER ONLY",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.fjordAccent
                    )
                    Spacer(Modifier.height(8.dp))
                    visibleInsights.forEach { insight ->
                        val accent = when (insight.severity) {
                            com.kanso.spending.domain.insights.InsightSeverity.GOOD -> colors.positiveAccent
                            com.kanso.spending.domain.insights.InsightSeverity.WARNING -> colors.warningAccent
                            com.kanso.spending.domain.insights.InsightSeverity.INFO -> colors.fjordAccent
                        }
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text("· ", style = MaterialTheme.typography.bodySmall, color = accent)
                            Text(
                                text = insight.text + if (insight.isEstimate) " (estimate)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.inkSecondary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Observations compare figures you recorded — no predictions are counted as spending.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                }
            }
        }
    }
}

/** Entry points for every v2.0 feature, in one scrollable row. */
@Composable
private fun SmartActionRow(
    smartState: SmartLedgerUiState,
    onOpenSms: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenQuickEntry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalKansoColors.current
    val pending = smartState.pendingDetections.size

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text("INTELLIGENCE", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                if (pending > 0) "SMART SMS · $pending" else "SMART SMS",
                Icons.Outlined.Message, colors.positiveAccent, onOpenSms
            )
            ActionButton("ACCOUNTS", Icons.Outlined.AccountBalance, colors.fjordAccent, onOpenAccounts)
            ActionButton("CALENDAR", Icons.Outlined.CalendarMonth, colors.ochreAccent, onOpenCalendar)
            ActionButton("RECURRING", Icons.Outlined.Repeat, colors.warningAccent, onOpenRecurring)
            ActionButton("GOALS", Icons.Outlined.Flag, colors.positiveAccent, onOpenGoals)
            ActionButton("QUICK ENTRY", Icons.Outlined.Bolt, colors.fjordAccent, onOpenQuickEntry)
            ActionButton("SMART SETTINGS", Icons.Outlined.Tune, colors.ochreAccent, onOpenSettings)
        }
    }
}

/** Available balance + month money-in / expenses strip. */
@Composable
private fun BalanceStripCard(uiState: SpendingDashboardUiState) {
    val colors = LocalKansoColors.current
    val a = uiState.analytics
    val sym = uiState.currencySymbol

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("AVAILABLE BALANCE", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Text(
                "OPENING + MONEY IN − EXPENSES",
                style = MaterialTheme.typography.labelSmall,
                fontStyle = FontStyle.Italic,
                color = colors.inkMuted
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            com.kanso.spending.domain.CurrencyFormatter.formatCents(uiState.availableBalanceCents, sym),
            style = MaterialTheme.typography.displayLarge,
            color = if (uiState.availableBalanceCents >= 0L) colors.inkPrimary else colors.alertAccent
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("MONEY IN • MONTH", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                Text(
                    "+${com.kanso.spending.domain.CurrencyFormatter.formatCentsCompact(a.moneyInCents, sym)}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = colors.positiveAccent
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("EXPENSES • MONTH", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
                Text(
                    "−${com.kanso.spending.domain.CurrencyFormatter.formatCentsCompact(a.totalSpentCents, sym)}",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = colors.inkPrimary
                )
            }
        }
    }
}

/** Payment-method-wise spending card, clickable to filter the history. */
@Composable
private fun PaymentMethodSpendCard(
    analytics: MonthlyLedgerAnalytics,
    currencySymbol: String,
    selectedMethod: String?,
    onSelectMethod: (String?) -> Unit
) {
    val colors = LocalKansoColors.current
    val rows = analytics.methodBreakdown

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("PAYMENT METHODS • MONTH", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
        if (rows.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No spending recorded with any payment method yet.",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = colors.inkMuted
            )
        } else {
            Spacer(Modifier.height(10.dp))
            rows.forEach { row ->
                val sel = selectedMethod == row.methodId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) colors.elevatedSurface else Color.Transparent)
                        .clickable { onSelectMethod(if (sel) null else row.methodId) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (row.provider.isNotBlank()) "${row.label} (${row.provider})" else row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkPrimary
                    )
                    Text(
                        CurrencyFormatter.formatCentsCompact(row.spentCents, currencySymbol) + " • ${row.transactionCount}",
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.inkSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun LedgerFilterAndSearchBar(
    uiState: SpendingDashboardUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectMethod: (String?) -> Unit,
    onTypeFilterChange: (LedgerTypeFilter) -> Unit
) {
    val colors = LocalKansoColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted) },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = colors.inkMuted)
                    }
                }
            },
            placeholder = { Text("Search merchant, note, or category...") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        // Type filter
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LedgerTypeFilter.entries.forEach { type ->
                FilterChipPill(
                    label = type.label,
                    selected = uiState.typeFilter == type,
                    accentColor = if (type == LedgerTypeFilter.MONEY_IN) colors.positiveAccent else colors.inkPrimary,
                    onClick = { onTypeFilterChange(type) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Category filter
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipPill(
                label = "ALL ENTRIES",
                selected = uiState.selectedCategoryFilter == null,
                accentColor = colors.inkPrimary,
                onClick = { onSelectCategory(null) }
            )
            uiState.categoryList.forEach { cat ->
                FilterChipPill(
                    label = cat.displayName.uppercase(),
                    selected = uiState.selectedCategoryFilter == cat.id,
                    accentColor = Color(cat.colorHex),
                    onClick = { onSelectCategory(cat.id) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Payment method filter
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipPill(
                label = "ALL METHODS",
                selected = uiState.selectedMethodFilter == null,
                accentColor = colors.inkPrimary,
                onClick = { onSelectMethod(null) }
            )
            PaymentMethod.entries.forEach { m ->
                FilterChipPill(
                    label = m.label.uppercase(),
                    selected = uiState.selectedMethodFilter == m.id,
                    accentColor = colors.inkPrimary,
                    onClick = { onSelectMethod(m.id) }
                )
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    val colors = LocalKansoColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accentColor.copy(alpha = 0.16f) else colors.surface)
            .border(1.dp, if (selected) accentColor else colors.hairlineBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accentColor else colors.inkSecondary
        )
    }
}

@Composable
private fun EmptyLedgerNotice(
    hasActiveFilter: Boolean,
    onClearFilters: () -> Unit
) {
    val colors = LocalKansoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (hasActiveFilter) "NO MATCHING ENTRIES" else "NO ENTRIES YET",
            style = MaterialTheme.typography.titleMedium,
            color = colors.inkPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasActiveFilter) {
                "Nothing in this month's local ledger matches the current filters."
            } else {
                "This month has no recorded money activity. Use ADD MONEY to set your opening balance, or RECORD EXPENSE to add your first transaction."
            },
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = colors.inkMuted
        )
        if (hasActiveFilter) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onClearFilters, shape = RoundedCornerShape(10.dp)) {
                Text("CLEAR FILTERS", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
