package com.kanso.spending.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.components.*
import com.kanso.spending.ui.theme.LocalKansoColors

@Composable
fun SpendingDashboardScreen(
    uiState: SpendingDashboardUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectCategoryFilter: (SpendingCategory?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleTheme: () -> Unit,
    onSaveExpense: (Long?, String, Long, SpendingCategory, PaymentMethod, Long, String, Boolean) -> Unit,
    onDeleteExpense: (ExpenseEntity) -> Unit,
    onSaveBudgetConfig: (Long, String, Map<SpendingCategory, Long>) -> Unit,
    onGenerateBackupJson: () -> Unit,
    onImportBackupJson: (String) -> Unit,
    onWipeAllLocalData: () -> Unit,
    onClearVaultMessage: () -> Unit
) {
    val colors = LocalKansoColors.current
    var showExpenseSheet by remember { mutableStateOf(false) }
    var editingExpense by remember { mutableStateOf<ExpenseEntity?>(null) }
    var showBudgetSheet by remember { mutableStateOf(false) }
    var showVaultSheet by remember { mutableStateOf(false) }

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
                    searchQuery = uiState.searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    selectedCategory = uiState.selectedCategoryFilter,
                    onSelectCategory = onSelectCategoryFilter
                )
            }

            if (uiState.analytics.daySections.isEmpty()) {
                item {
                    EmptyLedgerNotice(
                        hasActiveFilter = uiState.selectedCategoryFilter != null || uiState.searchQuery.isNotBlank(),
                        onClearFilters = {
                            onSelectCategoryFilter(null)
                            onSearchQueryChange("")
                        }
                    )
                }
            } else {
                items(uiState.analytics.daySections, key = { it.date.toEpochDay() }) { section ->
                    DayLedgerSectionCard(
                        section = section,
                        currencySymbol = uiState.currencySymbol,
                        onEditExpense = { exp ->
                            editingExpense = exp
                            showExpenseSheet = true
                        },
                        onDeleteExpense = onDeleteExpense
                    )
                }
            }
        }
    }

    if (showExpenseSheet) {
        TactileExpenseSheet(
            editingExpense = editingExpense,
            currencySymbol = uiState.currencySymbol,
            merchantSuggestions = uiState.analytics.merchantSuggestions.map { it.title },
            onSaveExpense = onSaveExpense,
            onDismiss = {
                showExpenseSheet = false
                editingExpense = null
            }
        )
    }

    if (showBudgetSheet) {
        BudgetPlannerSheet(
            currentMonthlyBudgetCents = uiState.analytics.monthlyBudgetCents,
            categoryBreakdown = uiState.analytics.categoryBreakdown,
            currencySymbol = uiState.currencySymbol,
            onSaveConfiguration = onSaveBudgetConfig,
            onDismiss = { showBudgetSheet = false }
        )
    }

    if (showVaultSheet) {
        LocalVaultSheet(
            backupJsonPreview = uiState.backupJsonPreview,
            statusMessage = uiState.vaultStatusMessage,
            onGenerateBackupJson = onGenerateBackupJson,
            onImportBackupJson = onImportBackupJson,
            onWipeAllLocalData = onWipeAllLocalData,
            onDismiss = {
                onClearVaultMessage()
                showVaultSheet = false
            }
        )
    }
}

@Composable
private fun LedgerFilterAndSearchBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: SpendingCategory?,
    onSelectCategory: (SpendingCategory?) -> Unit
) {
    val colors = LocalKansoColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.inkMuted) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
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

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChipPill(
                label = "ALL ENTRIES",
                selected = selectedCategory == null,
                accentColor = colors.inkPrimary,
                onClick = { onSelectCategory(null) }
            )
            SpendingCategory.entries.forEach { cat ->
                FilterChipPill(
                    label = cat.displayName.uppercase(),
                    selected = selectedCategory == cat,
                    accentColor = Color(cat.accentHex),
                    onClick = { onSelectCategory(cat) }
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
                "Nothing in this month's local ledger matches the current filter."
            } else {
                "This month has no recorded spending. Use RECORD EXPENSE to add your first entry."
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
