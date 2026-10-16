package com.kanso.spending.domain.accounts

import com.kanso.spending.data.local.entity.AccountAdjustmentEntity
import com.kanso.spending.data.local.entity.AccountEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.TransferEntity
import com.kanso.spending.data.model.PaymentMethod

/**
 * Pure assembly of per-account balance states from Room rows.
 *
 * Mapping rules:
 *  - a row carrying a valid `account_id` belongs to that account;
 *  - rows written before v3 (blank id) or referencing a removed account fall
 *    back to the account implied by their payment method — never to nothing;
 *  - transfers contribute a transfer-out on the source and a transfer-in on
 *    the destination, and a PENDING transfer changes nothing confirmed.
 */
object AccountStateFactory {

    fun states(
        accounts: List<AccountEntity>,
        expenses: List<ExpenseEntity>,
        incomes: List<IncomeEntity>,
        transfers: List<TransferEntity>,
        adjustments: List<AccountAdjustmentEntity>
    ): List<AccountState> {
        val knownIds = accounts.map { it.id }.toSet()
        val entries = ArrayList<AccountEntry>(expenses.size + incomes.size + transfers.size * 2)

        expenses.forEach { expense ->
            entries += AccountEntry(
                accountId = resolve(expense.accountId, expense.paymentMethod, knownIds),
                outCents = expense.amountCents,
                feeCents = expense.feeCents
            )
        }
        incomes.forEach { income ->
            entries += AccountEntry(
                accountId = resolve(income.accountId, income.paymentMethod, knownIds),
                inCents = income.amountCents
            )
        }
        transfers.forEach { transfer ->
            val confirmed = !transfer.status.equals("PENDING", ignoreCase = true)
            entries += AccountEntry(
                accountId = transfer.fromAccountId,
                outCents = transfer.amountCents,
                feeCents = transfer.feeCents,
                isTransferOut = true,
                isConfirmed = confirmed
            )
            entries += AccountEntry(
                accountId = transfer.toAccountId,
                inCents = transfer.amountCents,
                isTransferIn = true,
                isConfirmed = confirmed
            )
        }

        val domainAdjustments = adjustments.map { adjustment ->
            AccountAdjustment(
                accountId = adjustment.accountId,
                deltaCents = adjustment.deltaCents,
                kind = runCatching { AdjustmentKind.valueOf(adjustment.kind) }
                    .getOrDefault(AdjustmentKind.MANUAL)
            )
        }

        return accounts.map { AccountBalanceEngine.state(it.toSnapshot(), entries, domainAdjustments) }
    }

    /**
     * Blank or dangling account id → the account implied by the payment
     * method, so no row ever silently escapes every balance.
     */
    fun resolve(accountId: String, paymentMethod: String, knownAccountIds: Set<String>): String =
        if (accountId.isNotBlank() && accountId in knownAccountIds) accountId
        else defaultAccountIdForMethod(paymentMethod)

    /** Payment-method → structural account (mirrors the v2→v3 migration SQL). */
    fun defaultAccountIdForMethod(paymentMethod: String): String =
        when (PaymentMethod.resolveId(paymentMethod)) {
            PaymentMethod.MOBILE_WALLET -> AccountEntity.WALLET_ID
            PaymentMethod.DEBIT_CARD, PaymentMethod.CREDIT_CARD -> AccountEntity.CARD_ID
            PaymentMethod.BANK_TRANSFER -> AccountEntity.BANK_ID
            else -> AccountEntity.CASH_ID
        }

    fun AccountEntity.toSnapshot(): AccountSnapshot = AccountSnapshot(
        id = id,
        name = name,
        type = runCatching { AccountType.valueOf(type) }.getOrDefault(AccountType.CASH),
        openingBalanceCents = openingBalanceCents,
        isArchived = isArchived,
        sortOrder = sortOrder,
        lastReportedBalanceCents = lastReportedBalanceCents,
        lastReportedAtMillis = lastReportedAtMillis,
        note = note
    )
}
