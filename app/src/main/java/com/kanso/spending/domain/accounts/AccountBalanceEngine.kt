package com.kanso.spending.domain.accounts

/**
 * Account kinds. Each account keeps its own balance and history.
 * CARD balances are spendable funds (≤0 means debt) unless used as credit.
 */
enum class AccountType { CASH, BANK, WALLET, CARD }

enum class AdjustmentKind { OPENING, MANUAL, RECONCILE }

/** One immutable ledger fact affecting an account balance. */
data class AccountEntry(
    val accountId: String,
    val inCents: Long = 0L,        // money added
    val outCents: Long = 0L,       // money removed
    val feeCents: Long = 0L,       // fees debited with the entry
    val isTransferIn: Boolean = false,
    val isTransferOut: Boolean = false,
    val isConfirmed: Boolean = true
)

data class AccountAdjustment(
    val accountId: String,
    val deltaCents: Long,          // signed
    val kind: AdjustmentKind = AdjustmentKind.MANUAL
)

data class AccountState(
    val accountId: String,
    val name: String,
    val type: AccountType,
    val openingBalanceCents: Long,
    val isArchived: Boolean,
    val entries: List<AccountEntry>,
    val adjustments: List<AccountAdjustment>,
    /** Provider-reported balance from the latest SMS, if any. */
    val lastReportedBalanceCents: Long? = null,
    val lastReportedAtMillis: Long? = null
) {
    /** Confirmed incoming money (non-transfer) into this account. */
    val incomingCents: Long
        get() = entries.filter { it.isConfirmed && !it.isTransferIn }.sumOf { it.inCents }

    /** Confirmed outgoing money (non-transfer) from this account, plus every
     *  fee (fees are real outflows even when attached to a transfer). */
    val outgoingCents: Long
        get() = entries.filter { it.isConfirmed && !it.isTransferOut }.sumOf { it.outCents } +
            entries.filter { it.isConfirmed }.sumOf { it.feeCents }

    val transfersInCents: Long
        get() = entries.filter { it.isConfirmed && it.isTransferIn }.sumOf { it.inCents }

    val transfersOutCents: Long
        get() = entries.filter { it.isConfirmed && it.isTransferOut }.sumOf { it.outCents }

    /** Total fees paid from this account (reported separately from spending). */
    val feesCents: Long
        get() = entries.filter { it.isConfirmed }.sumOf { it.feeCents }

    /** Pending (unreviewed) activity is tracked but never counted here. */
    val pendingInCents: Long
        get() = entries.filter { !it.isConfirmed && !it.isTransferIn }.sumOf { it.inCents }

    val pendingOutCents: Long
        get() = entries.filter { !it.isConfirmed && !it.isTransferOut }.sumOf { it.outCents } +
            entries.filter { !it.isConfirmed }.sumOf { it.feeCents }

    val pendingTransfersInCents: Long
        get() = entries.filter { !it.isConfirmed && it.isTransferIn }.sumOf { it.inCents }

    val pendingTransfersOutCents: Long
        get() = entries.filter { !it.isConfirmed && it.isTransferOut }.sumOf { it.outCents }

    val adjustmentsCents: Long
        get() = adjustments.sumOf { it.deltaCents }

    /**
     * Opening Balance + Incoming − Outgoing + Adjustments = Calculated Balance.
     * Transfers move money between accounts and net to zero across the ledger.
     */
    val calculatedBalanceCents: Long
        get() = openingBalanceCents + incomingCents - outgoingCents + adjustmentsCents +
            transfersInCents - transfersOutCents

    /** Balance including pending items — for display only, never confirmed totals. */
    val balanceIfApprovedCents: Long
        get() = calculatedBalanceCents + pendingInCents - pendingOutCents +
            pendingTransfersInCents - pendingTransfersOutCents

    /** Credit-card debt: positive when the card owes money. */
    val creditDebtCents: Long
        get() = if (type == AccountType.CARD) -calculatedBalanceCents.coerceAtMost(0L) else 0L

    /** Reconciliation: ledger vs provider-reported, null when no report exists. */
    val reconciliation: Reconciliation?
        get() {
            val reported = lastReportedBalanceCents ?: return null
            val diff = reported - calculatedBalanceCents
            return Reconciliation(
                reportedBalanceCents = reported,
                ledgerBalanceCents = calculatedBalanceCents,
                differenceCents = diff,
                isInAgreement = diff == 0L,
                reportedAtMillis = lastReportedAtMillis
            )
        }
}

data class Reconciliation(
    val reportedBalanceCents: Long,
    val ledgerBalanceCents: Long,
    val differenceCents: Long,
    val isInAgreement: Boolean,
    val reportedAtMillis: Long?
)

object AccountBalanceEngine {

    fun state(
        account: AccountSnapshot,
        entries: List<AccountEntry>,
        adjustments: List<AccountAdjustment>
    ): AccountState = AccountState(
        accountId = account.id,
        name = account.name,
        type = account.type,
        openingBalanceCents = account.openingBalanceCents,
        isArchived = account.isArchived,
        entries = entries.filter { it.accountId == account.id },
        adjustments = adjustments.filter { it.accountId == account.id },
        lastReportedBalanceCents = account.lastReportedBalanceCents,
        lastReportedAtMillis = account.lastReportedAtMillis
    )

    /** Total net funds across every non-archived account (card debt subtracts). */
    fun netWorth(states: List<AccountState>): Long =
        states.filter { !it.isArchived }.sumOf { it.calculatedBalanceCents }

    /**
     * A transfer decreases the source and increases the destination; it never
     * counts as income or expense. Returns updated states (pure).
     */
    fun applyTransfer(
        states: List<AccountState>,
        fromAccountId: String,
        toAccountId: String,
        amountCents: Long,
        feeCents: Long = 0L
    ): List<AccountState> {
        require(fromAccountId != toAccountId) { "transfer endpoints must differ" }
        require(amountCents > 0) { "transfer amount must be positive" }
        return states.map { s ->
            when (s.accountId) {
                fromAccountId -> s.copy(
                    entries = s.entries + AccountEntry(
                        accountId = s.accountId,
                        outCents = amountCents,
                        feeCents = feeCents,
                        isTransferOut = true
                    )
                )
                toAccountId -> s.copy(
                    entries = s.entries + AccountEntry(
                        accountId = s.accountId,
                        inCents = amountCents,
                        isTransferIn = true
                    )
                )
                else -> s
            }
        }
    }

    /**
     * Compare a provider-reported balance with the calculated balance.
     * Reports are stored beside — never in place of — ledger truth.
     */
    fun reconcile(state: AccountState): Reconciliation? = state.reconciliation
}
