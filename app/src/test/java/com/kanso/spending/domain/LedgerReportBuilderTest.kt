package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.MoneySource
import com.kanso.spending.data.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class LedgerReportBuilderTest {

    private val zone = ZoneId.systemDefault()

    private fun millis(date: LocalDate, hour: Int = 12): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun expense(id: Long, amountCents: Long, categoryId: String, date: LocalDate, method: PaymentMethod = PaymentMethod.CASH) =
        ExpenseEntity(
            id = id,
            title = "Desc $id",
            amountCents = amountCents,
            categoryId = categoryId,
            paymentMethod = method.id,
            timestampEpochMillis = millis(date)
        )

    private fun income(id: Long, amountCents: Long, source: MoneySource, date: LocalDate, method: PaymentMethod = PaymentMethod.CASH) =
        IncomeEntity(
            id = id,
            title = "In $id",
            amountCents = amountCents,
            sourceId = source.id,
            paymentMethod = method.id,
            timestampEpochMillis = millis(date)
        )

    private val label = { id: String ->
        mapOf("dining" to "Food & Dining", "groceries" to "Groceries", "transit" to "Transportation")[id] ?: id
    }

    @Test
    fun `resolves all period ranges including week and month boundaries`() {
        val anchor = LocalDate.of(2026, 10, 15) // Thursday

        val today = LedgerReportRanges.resolve(LedgerReportPeriod.TODAY, anchor)
        assertEquals(LocalDate.of(2026, 10, 15), today.start)
        assertEquals(LocalDate.of(2026, 10, 15), today.endInclusive)

        val yesterday = LedgerReportRanges.resolve(LedgerReportPeriod.YESTERDAY, anchor)
        assertEquals(LocalDate.of(2026, 10, 14), yesterday.start)

        val last7 = LedgerReportRanges.resolve(LedgerReportPeriod.LAST_7_DAYS, anchor)
        assertEquals(LocalDate.of(2026, 10, 9), last7.start)

        val thisWeek = LedgerReportRanges.resolve(LedgerReportPeriod.THIS_WEEK, anchor)
        assertEquals(DayOfWeek.MONDAY, thisWeek.start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 10, 12), thisWeek.start)
        assertEquals(LocalDate.of(2026, 10, 18), thisWeek.endInclusive)

        val lastWeek = LedgerReportRanges.resolve(LedgerReportPeriod.LAST_WEEK, anchor)
        assertEquals(LocalDate.of(2026, 10, 5), lastWeek.start)
        assertEquals(LocalDate.of(2026, 10, 11), lastWeek.endInclusive)

        val thisMonth = LedgerReportRanges.resolve(LedgerReportPeriod.THIS_MONTH, anchor)
        assertEquals(LocalDate.of(2026, 10, 1), thisMonth.start)
        assertEquals(LocalDate.of(2026, 10, 31), thisMonth.endInclusive)

        val lastMonth = LedgerReportRanges.resolve(LedgerReportPeriod.LAST_MONTH, anchor)
        assertEquals(LocalDate.of(2026, 9, 1), lastMonth.start)
        assertEquals(LocalDate.of(2026, 9, 30), lastMonth.endInclusive)

        val custom = LedgerReportRanges.resolve(
            LedgerReportPeriod.CUSTOM, anchor,
            LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 8)
        )
        assertEquals(LocalDate.of(2026, 10, 3), custom.start)
        assertEquals(LocalDate.of(2026, 10, 8), custom.endInclusive)
    }

    @Test
    fun `report balances follow opening plus money-in minus expenses`() {
        // History before the range: opening balance was 1000 − 400 = ৳6.00
        val before = listOf(income(1, 1_000L, MoneySource.INITIAL_BALANCE, LocalDate.of(2026, 10, 1)))
        val beforeExpense = listOf(expense(2, 400L, "dining", LocalDate.of(2026, 10, 2)))

        val expenses = listOf(
            beforeExpense[0],
            expense(3, 250L, "groceries", LocalDate.of(2026, 10, 10)),
            expense(4, 150L, "transit", LocalDate.of(2026, 10, 11))
        )
        val incomes = listOf(before[0], income(5, 2_000L, MoneySource.SALARY, LocalDate.of(2026, 10, 12)))

        val range = LedgerReportRange(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 14))
        val report = LedgerReportBuilder.build(range, expenses, incomes, zone, categoryLabel = label)

        assertEquals(600L, report.openingBalanceCents)       // 1000 − 400
        assertEquals(2_000L, report.moneyInCents)            // salary only, in range
        assertEquals(400L, report.expensesCents)             // 250 + 150 (out-of-range 400 excluded)
        assertEquals(2_200L, report.closingBalanceCents)     // 600 + 2000 − 400
        assertEquals(3, report.transactionCount)
    }

    @Test
    fun `refund counts once as money added and never as an expense reduction`() {
        val expenses = listOf(expense(1, 900L, "dining", LocalDate.of(2026, 10, 10)))
        val incomes = listOf(income(2, 300L, MoneySource.REFUND, LocalDate.of(2026, 10, 11)))
        val range = LedgerReportRange(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11))

        val report = LedgerReportBuilder.build(range, expenses, incomes, zone, categoryLabel = label)

        assertEquals(900L, report.expensesCents)      // refund did NOT reduce expenses
        assertEquals(300L, report.moneyInCents)       // refund appeared once as money added
        assertEquals(-600L, report.closingBalanceCents)
        assertEquals(2, report.transactionCount)
        assertTrue(report.lines.none { it.sourceLabel == MoneySource.REFUND.label && !it.isIncome })
    }

    @Test
    fun `running balances are chronological with no duplicated transactions`() {
        val d1 = LocalDate.of(2026, 10, 20)
        val d2 = LocalDate.of(2026, 10, 21)
        val d3 = LocalDate.of(2026, 10, 21)
        val expenses = listOf(
            expense(1, 100L, "dining", d1),
            expense(2, 200L, "dining", d2, PaymentMethod.MOBILE_WALLET),
            expense(3, 300L, "groceries", d3)
        )
        val incomes = listOf(income(4, 1_500L, MoneySource.FREELANCE, d1, PaymentMethod.MOBILE_WALLET))

        val report = LedgerReportBuilder.build(
            LedgerReportRange(d1, d3), expenses, incomes, zone, categoryLabel = label
        )

        // 3 expenses + 1 income, each exactly once
        assertEquals(4, report.lines.size)
        assertEquals(4, report.transactionCount)
        assertEquals(1_500L, report.lines.first().runningBalanceCents)
        assertEquals(1_400L, report.lines[1].runningBalanceCents)
        assertEquals(1_200L, report.lines[2].runningBalanceCents)
        assertEquals(900L, report.closingBalanceCents)

        // Category breakdown de-duplicates by label and sums the same rows exactly
        assertEquals(300L, report.categoryBreakdown.first { it.label == "Food & Dining" }.spentCents)
        assertEquals(2, report.categoryBreakdown.first { it.label == "Food & Dining" }.transactionCount)
        assertEquals(300L, report.categoryBreakdown.first { it.label == "Groceries" }.spentCents)
        assertEquals(2, report.categoryBreakdown.size)
    }

    @Test
    fun `csv totals reconcile with the report figures`() {
        val d1 = LocalDate.of(2026, 10, 10)
        val expenses = listOf(
            expense(1, 1_234L, "groceries", d1),
            expense(2, 456L, "transit", d1, PaymentMethod.DEBIT_CARD)
        )
        val incomes = listOf(income(3, 10_000L, MoneySource.SALARY, d1))

        val report = LedgerReportBuilder.build(
            LedgerReportRange(d1, d1), expenses, incomes, zone, categoryLabel = label
        )
        val csv = LedgerReportCsvWriter.toCsv(report, zone)

        // Only the transaction data rows contain "MONEY IN" / "EXPENSE" types.
        val dataRows = csv.lines().filter {
            it.startsWith("\"") && (it.contains("\"MONEY IN\"") || it.contains("\"EXPENSE\""))
        }
        assertEquals(3, dataRows.size)
        val inTotal = dataRows
            .filter { it.contains("\"MONEY IN\"") }
            .sumOf { Regex("\"MONEY IN\"[^\\r\\n]*\"(\\d+)\",\"0\",\"(-?\\d+)\"").find(it)!!.groupValues[1].toLong() }
        val outTotal = dataRows
            .filter { it.contains("\"EXPENSE\"") }
            .sumOf { Regex("\"EXPENSE\"[^\\r\\n]*\"0\",\"(\\d+)\",\"").find(it)!!.groupValues[1].toLong() }

        assertEquals(report.moneyInCents, inTotal)
        assertEquals(report.expensesCents, outTotal)
        assertTrue(csv.contains("Opening Balance (cents)"))
        assertTrue(csv.contains("Transaction Count"))
    }
}
