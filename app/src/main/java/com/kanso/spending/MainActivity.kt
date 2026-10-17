package com.kanso.spending

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.kanso.spending.notifications.AppNotifier
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SpendingViewModel
import com.kanso.spending.ui.components.SmartOnboardingScreen
import com.kanso.spending.ui.screens.SpendingDashboardScreen
import com.kanso.spending.ui.theme.KansoTheme
import com.kanso.spending.ui.theme.LocalKansoColors
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val viewModel: SpendingViewModel by viewModels {
        val app = application as KansoApplication
        SpendingViewModel.Factory(app.repository)
    }

    private val appLocked = mutableStateOf(false)

    /** Mirror of the persisted first-launch setup outcome (onboarding shown once). */
    private val smartSetupDone = mutableStateOf(false)

    private val smartActions: SmartLedgerActions by lazy { buildSmartActions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as KansoApplication).repository
        // Behind the lock on cold start and after process death; rotation keeps
        // the process-wide flag so the prompt never fires mid-rotation.
        appLocked.value = repository.smartSettings.value.appLockEnabled && !processUnlocked
        smartSetupDone.value = repository.isSmartSetupDone()

        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        lifecycleScope.launch {
            runCatching { AppNotifier.checkAndNotify(this@MainActivity, repository) }
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val smartState by viewModel.smartState.collectAsState()
            val locked by appLocked
            var promptAttempt by remember { mutableStateOf(0) }
            var lockFallback by remember { mutableStateOf(false) }

            // Prompt whenever the lock turns on (cold start, background return,
            // or the user just enabled it in Smart Settings).
            LaunchedEffect(locked) {
                if (locked) {
                    lockFallback = false
                    promptAttempt += 1
                }
            }
            LaunchedEffect(promptAttempt) {
                if (locked && promptAttempt > 0) {
                    authenticateWithDevice(
                        onUnavailable = { lockFallback = true }
                    )
                }
            }
            LaunchedEffect(smartState.smartSettings.appLockEnabled) {
                if (smartState.smartSettings.appLockEnabled && !appLocked.value) {
                    processUnlocked = false
                    appLocked.value = true
                }
            }

            KansoTheme(darkTheme = uiState.isDarkTheme) {
                // The dashboard stays composed underneath the lock so pending
                // report/backup flows survive an interruption; the opaque
                // overlay simply sits on top.
                Box(modifier = Modifier.fillMaxSize()) {
                    SpendingDashboardScreen(
                        uiState = uiState,
                        smartState = smartState,
                        smartActions = smartActions,
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

                    if (locked) {
                        AppLockOverlay(
                            onUnlockTap = {
                                lockFallback = false
                                promptAttempt += 1
                            },
                            showFallback = lockFallback,
                            onFallbackUnlock = {
                                processUnlocked = true
                                appLocked.value = false
                            }
                        )
                    }

                    // First-launch Smart Setup: an opaque screen over the still
                    // composed dashboard (same pattern as the lock), dismissed
                    // exactly once after the user's explicit choice.
                    if (!smartSetupDone.value) {
                        SmartOnboardingScreen(
                            currencySymbol = uiState.currencySymbol,
                            actions = smartActions,
                            onFinish = {
                                repository.markSmartSetupDone()
                                smartSetupDone.value = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Privacy screen: leaving the app re-locks it (rotation exempt).
        if (!isChangingConfigurations) {
            val repository = (application as KansoApplication).repository
            if (repository.smartSettings.value.appLockEnabled) {
                processUnlocked = false
                appLocked.value = true
            }
        }
    }

    /** Share-sheet import: another app sent us plain text to parse. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                viewModel.importPastedSms(text)
            }
        }
    }

    private fun authenticateWithDevice(onUnavailable: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    processUnlocked = true
                    appLocked.value = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> onUnavailable()
                        // USER_CANCELED / LOCKOUT / etc: stay locked, the
                        // UNLOCK button lets the user retry.
                    }
                }
            }
        )
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kanso Ledger")
            .setSubtitle("Privacy screen — this does not encrypt your data")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        runCatching { prompt.authenticate(builder.build()) }
            .onFailure { onUnavailable() }
    }

    private fun buildSmartActions() = SmartLedgerActions(
        // ---- accounts & transfers
        onCreateAccount = { name, kind, opening, note ->
            viewModel.createAccount(name, kind, opening, note)
        },
        onRenameAccount = { id, name -> viewModel.renameAccount(id, name) },
        onSetAccountNote = { id, note -> viewModel.setAccountNote(id, note) },
        onSetAccountOpening = { id, cents -> viewModel.setAccountOpeningBalance(id, cents) },
        onSetAccountArchived = { id, archived -> viewModel.setAccountArchived(id, archived) },
        onDeleteAccount = { viewModel.deleteAccount(it) },
        onRecordAdjustment = { id, delta, note -> viewModel.recordAccountAdjustment(id, delta, note) },
        onCreateTransfer = { from, to, amount, fee, note ->
            viewModel.createTransfer(from, to, amount, fee, note)
        },
        onDeleteTransfer = { viewModel.deleteTransfer(it) },
        // ---- smart SMS
        onSetSmsEnabled = { viewModel.setSmsEnabled(it) },
        onSetImportMode = { viewModel.setSmsImportMode(it) },
        onImportHistory = { ctx, from, to -> viewModel.importSmsHistory(ctx, from, to) },
        onPreviewHistory = { ctx, from, to, onResult ->
            viewModel.previewSmsHistory(ctx, from, to, onResult)
        },
        onImportHistoryAs = { ctx, from, to, mode ->
            viewModel.importSmsHistoryAs(ctx, from, to, mode)
        },
        onPasteImport = { viewModel.importPastedSms(it) },
        onUndoLastImport = { viewModel.undoLastImport() },
        onClearSmsHistory = { viewModel.clearSmsImportHistory() },
        onApproveDetection = { id, approval -> viewModel.approveDetection(id, approval) },
        onRejectDetection = { viewModel.rejectDetection(it) },
        // ---- notifications & app lock
        onSetNotifyDetected = { viewModel.setNotifyNewDetections(it) },
        onSetNotifyBudget = { viewModel.setNotifyBudgetWarnings(it) },
        onSetNotifyReconcile = { viewModel.setNotifyReconciliation(it) },
        onSetNotifyRecurring = { viewModel.setNotifyRecurringDue(it) },
        onSetAppLock = { viewModel.setAppLockEnabled(it) },
        // ---- recurring
        onSaveRecurring = { id, title, amount, cat, interval, due, acc ->
            viewModel.saveRecurringRule(id, title, amount, cat, interval, due, acc)
        },
        onDeleteRecurring = { viewModel.deleteRecurringRule(it) },
        onSetRecurringActive = { id, active -> viewModel.setRecurringRuleActive(id, active) },
        onMarkRecurringPaid = { viewModel.markRecurringPaid(it) },
        onConfirmCandidate = { candidate, cat -> viewModel.confirmRecurringCandidate(candidate, cat) },
        // ---- goals
        onSaveGoal = { id, title, target, deadline, acc ->
            viewModel.saveSavingsGoal(id, title, target, deadline, acc)
        },
        onDeleteGoal = { viewModel.deleteSavingsGoal(it) },
        onAddContribution = { goal, amount, acc -> viewModel.addGoalContribution(goal, amount, acc) },
        onDeleteContribution = { viewModel.deleteGoalContribution(it) },
        // ---- categorisation rules
        onSaveCategoryRule = { keyword, cat -> viewModel.saveCategoryRule(keyword, cat) },
        onDeleteCategoryRule = { viewModel.deleteCategoryRule(it) },
        // ---- optional AI diagnostics & narrative
        onTestAiConnection = { onResult -> viewModel.testAiConnection(onResult) },
        onGenerateAiNarrative = { income, expense, top, onResult ->
            viewModel.generateAiNarrative(income, expense, top, onResult)
        },
        onClearSmartStatus = { viewModel.clearSmartStatusMessage() }
    )

    private companion object {
        /**
         * Process-wide "currently past the lock" flag: survives rotation (so
         * configuration changes never re-prompt), resets on process death
         * (so a restored background process is locked again).
         */
        @Volatile
        var processUnlocked: Boolean = false
    }
}

/**
 * Full-screen lock. Honest about what it is: a privacy screen in front of an
 * already-unlocked device, not encryption of the database.
 */
@Composable
private fun AppLockOverlay(
    onUnlockTap: () -> Unit,
    showFallback: Boolean,
    onFallbackUnlock: () -> Unit
) {
    val colors = LocalKansoColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "KANSO LEDGER",
                style = MaterialTheme.typography.labelSmall,
                color = colors.fjordAccent
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Locked",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.inkPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your ledger is hidden behind your fingerprint, face or screen " +
                    "unlock. This is a privacy screen — it does not encrypt your data.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onUnlockTap,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.inkPrimary,
                    contentColor = colors.surface
                )
            ) {
                Text(
                    "UNLOCK",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showFallback) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onFallbackUnlock,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "CONTINUE — NO SCREEN LOCK ENROLLED",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "This device has no fingerprint, face or screen lock the app can " +
                        "ask for, so the lock cannot mean anything here. Enrol one in " +
                        "Android settings to make this feature real.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "The app re-locks whenever it goes to the background.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.inkMuted,
                textAlign = TextAlign.Center
            )
        }
    }
}
