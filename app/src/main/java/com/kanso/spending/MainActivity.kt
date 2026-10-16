package com.kanso.spending

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kanso.spending.ui.SpendingViewModel
import com.kanso.spending.ui.screens.SpendingDashboardScreen
import com.kanso.spending.ui.theme.KansoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SpendingViewModel by viewModels {
        val app = application as KansoApplication
        SpendingViewModel.Factory(app.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val uiState by viewModel.uiState.collectAsState()

            KansoTheme(darkTheme = uiState.isDarkTheme) {
                SpendingDashboardScreen(
                    uiState = uiState,
                    onPreviousMonth = viewModel::selectPreviousMonth,
                    onNextMonth = viewModel::selectNextMonth,
                    onSelectCategoryFilter = viewModel::toggleCategoryFilter,
                    onSelectMethodFilter = viewModel::toggleMethodFilter,
                    onTypeFilterChange = viewModel::setTypeFilter,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onToggleTheme = viewModel::toggleDarkTheme,
                    onSaveExpense = viewModel::saveExpense,
                    onDeleteExpense = viewModel::deleteExpense,
                    onDeleteIncome = viewModel::deleteIncome,
                    onSaveMoneyIn = viewModel::saveMoneyIn,
                    onCreateCategory = viewModel::createCategory,
                    onRenameCategory = viewModel::renameCategory,
                    onDeleteCategory = viewModel::deleteCategory,
                    onSaveBudgetConfig = viewModel::saveBudgetConfiguration,
                    onPrepareReport = viewModel::prepareReport,
                    onClearPendingReport = viewModel::clearPendingReport,
                    onGenerateBackupJson = viewModel::generateLocalBackupJson,
                    onImportBackupJson = viewModel::importLocalBackupJson,
                    onWipeAllLocalData = viewModel::wipeAllLocalData,
                    onClearVaultMessage = viewModel::clearVaultStatusMessage
                )
            }
        }
    }
}
