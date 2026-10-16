package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.data.local.entity.TransferEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * v2.0 report contract: fees, SMS-vs-manual origin, transfers as their own
 * movement, unresolved reconciliation differences, and pending detections that
 * are counted but never money.
 */
class LedgerReportV2Test {

    private val zone = ZoneId.systemDefault()
    private val d = LocalDate.of(2026, 10, 15)

    private fun millis(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun expense(
        amountCents: Long,
        feeCents: Long = 0L,
        source: String = "MANUAL",
        date: LocalDate = d
    ) = ExpenseEntity(
        title = "Row",
        amountCents = amountCents,
        categoryId = "dining",
        paymentMethod = PaymentMethod.CASH.id,
        timestampEpochMillis = millis(date),
        feeCents = feeCents,
        source = source
    )

    private fun income(amountCents: Long, sourceId: String, source: String = "MANUAL") =
        IncomeEntity(
            title = "In",
            amountCents = amountCents,
            sourceId = sourceId,
            paymentMethod = PaymentMethod.CASH.id,
            timestampEpochMillis = millis(d),
            source = source
        )

    private fun detection(status: String, amountCents: Long?, fp: String) =
        SmsDetectionEntity(
            fingerprint = fp,
            sender = "bkash",
            provider = "bKash",
            txType = "MONEY_SENT",
            action = "REVIEW_ONLY",
            amountCents = amountCents,
            timestampEpochMillis = millis(d),
            confidence = 55,
            reviewStatus = status,
            createdAtMillis = millis(d),
            updatedAtMillis = millis(d)
        )

    private val range = LedgerReportRange(d, d)

    @Test
    fun `fees reduce balances but stay separable from expenses`() {
        val incomes = listOf(income(10_000L, MoneySource.SALARY.id))
        val expenses = listOf(expense(amountCents = 1_000L, feeCents = 50L))

        val report = LedgerReportBuilder.build(range, expenses, incomes, zone)

        assertEquals(1_000L, report.expensesCents)   // principal only
        assertEquals(50L, report.feesCents)          // reported separately
        assertEquals(8_950L, report.closingBalanceCents) // 10000 − 1000 − 50
        assertEquals(50L, report.lines.first { !it.isIncome }.feeCents)
        assertEquals(0L, report.lines.first { it.isIncome }.feeCents)
    }

    @Test
    fun `sms and manual origins are counted per record`() {
        val expenses = listOf(
            expense(500L, source = "SMS"),
            expense(700L, source = "MANUAL")
        )
        val incomes = listOf(income(2_000L, MoneySource.SALARY.id, source = "SMS"))

        val report = LedgerReportBuilder.build(range, expenses, incomes, zone)

        assertEquals(2, report.smsRowCount)
        assertEquals(1, report.manualRowCount)
        assertEquals(3, report.lines.size)
        assertEquals(2, report.lines.count { it.sourceTag == "SMS" })
    }

    @Test
    fun `transfers move money without income or expense totals`() {
        val transfers = listOf(
            TransferEntity(
                id = "t1",
                fromAccountId = "acc_bank",
                toAccountId = "acc_wallet",
                amountCents = 3_000L,
                feeCents = 15L,
                timestampEpochMillis = millis(d)
            ),
            TransferEntity(
                id = "t2",
                fromAccountId = "acc_wallet",
                toAccountId = "acc_cash",
                amountCents = 500L,
                timestampEpochMillis = millis(d)
            )
        )
        val incomes = listOf(income(4_000L, MoneySource.SALARY.id))
        val expenses = listOf(expense(1_000L))

        val report = LedgerReportBuilder.build(
            range, expenses, incomes, zone,
            transfers = transfers,
            accountName = { id -> mapOf("acc_bank" to "Bank", "acc_wallet" to "Wallet", "acc_cash" to "Cash")[id] ?: id }
        )

        assertEquals(3_500L, report.transfersCents)      // movement volume
        assertEquals(15L, report.transferFeesCents)      // real debit
        assertEquals(2, report.transfers.size)
        assertEquals("Bank", report.transfers[0].fromName)
        assertEquals("Wallet", report.transfers[0].toName)
        // Income/expense untouched by transfers themselves:
        assertEquals(4_000L, report.moneyInCents)
        assertEquals(1_000L, report.expensesCents)
        // Closing = 4000 − 1000 − 15 (transfer fee only — amounts not counted)
        assertEquals(2_985L, report.closingBalanceCents)
        assertEquals(2, report.transactionCount) // transfers are not ledger rows
    }

    @Test
    fun `pending detections are counted but never money`() {
        val expenses = listOf(expense(1_000L))
        val incomes = listOf(income(10_000L, MoneySource.SALARY.id))
        val pending = detection(status = "PENDING", amountCents = 777L, fp = "fp-pending")
        val approved = detection(status = "APPROVED", amountCents = 5_000L, fp = "fp-approved")
        val outside = detection(status = "PENDING", amountCents = 900L, fp = "fp-outside")
            .copy(timestampEpochMillis = millis(d.minusDays(30)))

        val report = LedgerReportBuilder.build(
            range, expenses, incomes, zone,
            pendingDetections = listOf(pending, approved, outside)
        )

        assertEquals(1, report.pendingCount)
        assertEquals(777L, report.pendingAmountCents)
        // Pending never leaks into confirmed figures:
        assertEquals(1_000L, report.expensesCents)
        assertEquals(10_000L, report.moneyInCents)
        assertEquals(2, report.transactionCount)
        assertEquals(9_000L, report.closingBalanceCents) // 10000 − 1000, pending ignored
    }

    @Test
    fun `refunds are broken out of money added`() {
        val incomes = listOf(
            income(5_000L, MoneySource.SALARY.id),
            income(300L, MoneySource.REFUND.id)
        )
        val report = LedgerReportBuilder.build(range, emptyList(), incomes, zone)

        assertEquals(5_300L, report.moneyInCents)
        assertEquals(300L, report.refundsCents)
    }

    @Test
    fun `csv carries fees origin transfers balances reconciliation and pending sections`() {
        val expenses = listOf(expense(1_000L, feeCents = 25L, source = "SMS"))
        val transfers = listOf(
            TransferEntity(
                id = "t1",
                fromAccountId = "acc_bank",
                toAccountId = "acc_cash",
                amountCents = 800L,
                timestampEpochMillis = millis(d)
            )
        )
        val report = LedgerReportBuilder.build(
            range, expenses, emptyList(), zone,
            transfers = transfers,
            accountName = { if (it == "acc_bank") "Bank" else if (it == "acc_cash") "Cash" else it },
            accountBalances = listOf(ReportAccountBalance("Bank", "BANK", 12_300L)),
            reconciliationWarnings = listOf(
                ReportReconciliationWarning("Bank", reportedCents = 12_000L, ledgerCents = 12_300L, differenceCents = -300L)
            ),
            pendingDetections = listOf(detection("PENDING", 400L, "fp1"))
        )
        val csv = LedgerReportCsvWriter.toCsv(report, zone)

        assertTrue(csv.contains("Fees (cents)"))
        assertTrue(csv.contains("Transfer fees deducted from balances (cents)"))
        assertTrue(csv.contains("\"Record Origin\""))
        assertTrue(csv.contains("Transfers (moved between accounts"))
        assertTrue(csv.contains("Current Account Balances (as generated)"))
        assertTrue(csv.contains("Unresolved Reconciliation Differences"))
        assertTrue(csv.contains("Unreviewed detections in period"))
        assertTrue(csv.contains("\"Bank\""))
        assertTrue(csv.contains("\"-300\""))
        // row carries fee + origin
        val dataRow = csv.lines().first { it.contains("\"EXPENSE\"") }
        assertTrue(dataRow.contains("\"25\""))
        assertTrue(dataRow.contains("\"SMS\""))
    }

    @Test
    fun `opening and closing agree across transfer fees on both sides of the range`() {
        val beforeTransfer = TransferEntity(
            id = "tb",
            fromAccountId = "acc_bank",
            toAccountId = "acc_cash",
            amountCents = 1_000L,
            feeCents = 20L,
            timestampEpochMillis = millis(d.minusDays(10))
        )
        val inRangeTransfer = TransferEntity(
            id = "ti",
            fromAccountId = "acc_bank",
            toAccountId = "acc_cash",
            amountCents = 500L,
            feeCents = 10L,
            timestampEpochMillis = millis(d)
        )
        val incomes = listOf(income(10_000L, MoneySource.SALARY.id))
        val expenses = listOf(expense(1_000L))

        val report = LedgerReportBuilder.build(
            range, expenses, incomes, zone,
            transfers = listOf(beforeTransfer, inRangeTransfer)
        )

        // No confirmed history before the range — only the prior transfer fee.
        assertEquals(-20L, report.openingBalanceCents)   // 0 − 20 prior transfer fee
        assertEquals(10L, report.transferFeesCents)      // only in-period fee
        assertEquals(8_970L, report.closingBalanceCents) // −20 + 10000 − 1000 − 10
    }
}
