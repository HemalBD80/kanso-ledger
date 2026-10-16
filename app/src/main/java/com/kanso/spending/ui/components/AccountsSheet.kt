package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.AccountKind
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.domain.KeypadExpressionEvaluator
import com.kanso.spending.domain.accounts.AccountState
import com.kanso.spending.ui.SmartLedgerActions
import com.kanso.spending.ui.SmartLedgerUiState
import com.kanso.spending.ui.theme.LocalKansoColors

/** Structural (seeded) accounts may be archived but never deleted. */
private fun smartStructural(accountId: String): Boolean =
    accountId in AccountEntity.STRUCTURAL_IDS

/**
 * Multi-account ledger: cash, bank, wallet and card with their own balances.
 * Transfers move money between accounts and are never income or expense;
 * provider-reported balances are shown next to the ledger's own calculation —
 * the calculated value is never silently rewritten.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(
    smartState: SmartLedgerUiState,
    currencySymbol: String,
    actions: SmartLedgerActions,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current
    var expandedAccount by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Text("MULTI-ACCOUNT LEDGER", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
            Spacer(Modifier.height(4.dp))
            Text("Your Accounts", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(12.dp))

            // ---- net funds across accounts
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.elevatedSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text("NET FUNDS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Text(
                        text = CurrencyFormatter.formatCents(smartState.netWorthCents, currencySymbol),
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (smartState.netWorthCents < 0) colors.alertAccent else colors.inkPrimary
                    )
                    Text(
                        text = "Opening balances + confirmed activity − spending − fees. " +
                            "Transfers move money without changing this total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                    if (smartState.reconciliationWarningCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "⚠ ${smartState.reconciliationWarningCount} account(s) differ from provider " +
                                "reports — review, nothing was overwritten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.warningAccent
                        )
                    }
                }
            }

            smartState.statusMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.fjordAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.elevatedSurface)
                        .padding(10.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            smartState.accountStates.forEach { state ->
                AccountCard(
                    state = state,
                    account = smartState.accounts.firstOrNull { it.id == state.accountId },
                    currencySymbol = currencySymbol,
                    expanded = expandedAccount == state.accountId,
                    onToggle = {
                        expandedAccount = if (expandedAccount == state.accountId) null else state.accountId
                    },
                    actions = actions
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(4.dp))
            if (showCreate) {
                CreateAccountForm(
                    currencySymbol = currencySymbol,
                    actions = actions,
                    onClose = { showCreate = false }
                )
            } else {
                OutlinedButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("+ ADD ACCOUNT", style = MaterialTheme.typography.labelLarge)
                }
            }

            TransferForm(
                smartState = smartState,
                currencySymbol = currencySymbol,
                actions = actions
            )

            smartState.transfers.take(10).let { recent ->
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text("RECENT TRANSFERS", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    Spacer(Modifier.height(6.dp))
                    recent.forEach { transfer ->
                        val from = smartState.accounts.firstOrNull { it.id == transfer.fromAccountId }?.name
                            ?: transfer.fromAccountId
                        val to = smartState.accounts.firstOrNull { it.id == transfer.toAccountId }?.name
                            ?: transfer.toAccountId
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "$from → $to",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.inkPrimary
                                )
                                Text(
                                    buildString {
                                        append(CurrencyFormatter.formatCents(transfer.amountCents, currencySymbol))
                                        if (transfer.feeCents > 0) append(
                                            " · fee ${CurrencyFormatter.formatCents(transfer.feeCents, currencySymbol)}"
                                        )
                                        if (transfer.note.isNotBlank()) append(" · ${transfer.note}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.inkMuted
                                )
                            }
                            TextButton(onClick = { actions.onDeleteTransfer(transfer.id) }) {
                                Text("DELETE", style = MaterialTheme.typography.labelSmall, color = colors.alertAccent)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AccountCard(
    state: AccountState,
    account: AccountEntity?,
    currencySymbol: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: SmartLedgerActions
) {
    val colors = LocalKansoColors.current
    var nameField by remember(expanded, state.accountId) { mutableStateOf(account?.name ?: state.name) }
    var hintField by remember(expanded, state.accountId) { mutableStateOf(account?.note ?: "") }
    var openingField by remember(expanded, state.accountId) {
        mutableStateOf(CurrencyFormatter.formatCentsPlain(state.openingBalanceCents).trim())
    }
    var adjustField by remember(expanded, state.accountId) { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) colors.elevatedSurface else colors.background,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (state.reconciliation?.isInAgreement == false) colors.warningAccent else colors.hairlineBorder
        )
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.name.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkPrimary
                        )
                        if (state.isArchived) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "ARCHIVED",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.inkMuted
                            )
                        }
                    }
                    Text(
                        "${state.type.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                            "opening ${CurrencyFormatter.formatCents(state.openingBalanceCents, currencySymbol)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.inkMuted
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        CurrencyFormatter.formatCents(state.calculatedBalanceCents, currencySymbol),
                        style = MaterialTheme.typography.titleLarge,
                        color = when {
                            state.calculatedBalanceCents < 0 -> colors.alertAccent
                            else -> colors.inkPrimary
                        }
                    )
                    Text(
                        if (expanded) "TAP TO CLOSE" else "TAP TO EDIT",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkMuted
                    )
                }
            }

            state.reconciliation?.let { rec ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (rec.isInAgreement) {
                        "Provider report agrees: ${CurrencyFormatter.formatCents(rec.reportedBalanceCents, currencySymbol)}"
                    } else {
                        "Provider reports ${CurrencyFormatter.formatCents(rec.reportedBalanceCents, currencySymbol)} " +
                            "(${CurrencyFormatter.formatCents(rec.differenceCents, currencySymbol, showPlusForPositive = true)} " +
                            "vs ledger). Choose an adjustment if the report is right — your history is never rewritten."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rec.isInAgreement) colors.positiveAccent else colors.warningAccent
                )
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text("Account name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = hintField,
                    onValueChange = { hintField = it },
                    label = { Text("Message hint (e.g. ***4512) — matching messages attach here") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = openingField,
                    onValueChange = { openingField = it },
                    label = { Text("Opening balance") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            actions.onRenameAccount(state.accountId, nameField)
                            actions.onSetAccountNote(state.accountId, hintField)
                            actions.onSetAccountOpening(
                                state.accountId,
                                KeypadExpressionEvaluator.evaluateToCents(openingField.ifBlank { "0" })
                            )
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.inkPrimary,
                            contentColor = colors.surface
                        )
                    ) { Text("SAVE", style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(
                        onClick = { actions.onSetAccountArchived(state.accountId, !state.isArchived) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            if (state.isArchived) "RESTORE" else "ARCHIVE",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("ADJUST BALANCE (signed)", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = adjustField,
                        onValueChange = { adjustField = it },
                        label = { Text("e.g. 120.50 or -30") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            val raw = adjustField.trim()
                            val negative = raw.startsWith("-")
                            val magnitude = KeypadExpressionEvaluator.evaluateToCents(
                                raw.removePrefix("-").removePrefix("+").ifBlank { "0" }
                            )
                            val signed = if (negative) -magnitude else magnitude
                            if (signed != 0L) {
                                actions.onRecordAdjustment(state.accountId, signed, "")
                                adjustField = ""
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("APPLY") }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { actions.onDeleteAccount(state.accountId) },
                    enabled = !smartStructural(state.accountId)
                ) {
                    Text(
                        if (smartStructural(state.accountId)) {
                            "BUILT-IN ACCOUNT — ARCHIVE INSTEAD"
                        } else {
                            "DELETE (only possible when empty)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (smartStructural(state.accountId)) colors.inkMuted else colors.alertAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateAccountForm(
    currencySymbol: String,
    actions: SmartLedgerActions,
    onClose: () -> Unit
) {
    val colors = LocalKansoColors.current
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(AccountKind.CASH) }
    var opening by remember { mutableStateOf("0") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.elevatedSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("NEW ACCOUNT", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (e.g. Dhaka Bank)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AccountKind.entries.forEach { k ->
                    val active = kind == k
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.background)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { kind = k }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            k.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.surface else colors.inkSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = opening,
                onValueChange = { opening = it },
                label = { Text("Opening balance ${currencySymbol}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("CANCEL") }
                Button(
                    onClick = {
                        actions.onCreateAccount(name, kind, KeypadExpressionEvaluator.evaluateToCents(opening.ifBlank { "0" }), "")
                        onClose()
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
                ) { Text("CREATE") }
            }
        }
    }
}

@Composable
private fun TransferForm(
    smartState: SmartLedgerUiState,
    currencySymbol: String,
    actions: SmartLedgerActions
) {
    val colors = LocalKansoColors.current
    val usable = smartState.accountStates.filter { !it.isArchived }
    var fromId by remember { mutableStateOf("") }
    var toId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    if (usable.size < 2) return

    Spacer(Modifier.height(16.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.elevatedSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairlineBorder)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("TRANSFER BETWEEN ACCOUNTS", style = MaterialTheme.typography.labelSmall, color = colors.fjordAccent)
            Spacer(Modifier.height(4.dp))
            Text(
                "Moves money without counting as income or expense.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted
            )
            Spacer(Modifier.height(8.dp))

            Text("FROM", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                usable.forEach { s ->
                    val active = fromId == s.accountId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.background)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { fromId = s.accountId }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.surface else colors.inkSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("TO", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                usable.forEach { s ->
                    val active = toId == s.accountId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.background)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { toId = s.accountId }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            s.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) colors.surface else colors.inkSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ${currencySymbol}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = fee,
                    onValueChange = { fee = it },
                    label = { Text("Fee ${currencySymbol}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            val amountCents = KeypadExpressionEvaluator.evaluateToCents(amount.ifBlank { "0" })
            Button(
                onClick = {
                    actions.onCreateTransfer(
                        fromId, toId, amountCents,
                        KeypadExpressionEvaluator.evaluateToCents(fee.ifBlank { "0" }),
                        note
                    )
                    amount = ""
                    fee = ""
                    note = ""
                },
                enabled = fromId.isNotBlank() && toId.isNotBlank() && fromId != toId && amountCents > 0L,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.fjordAccent, contentColor = colors.surface)
            ) {
                Text(
                    "SAVE TRANSFER${if (amountCents > 0) " · " + CurrencyFormatter.formatCents(amountCents, currencySymbol) else ""}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
