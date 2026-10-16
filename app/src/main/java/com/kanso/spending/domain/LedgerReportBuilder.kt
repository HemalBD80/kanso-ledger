package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.local.entity.SmsDetectionEntity
import com.kanso.spending.data.local.entity.TransferEntity
import com.kanso.spending.data.model.PaymentMethod
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Report period selections. Resolution rules:
 *  - This Week starts on Monday (ISO week) and ends Sunday.
 *  - Frozen ranges (Today / This Week / This Month / Custom) are anchored to the
 *    explicit [anchor] so tests and future kicks reproducibly resolve ranges.
 */
enum class LedgerReportPeriod {
    TODAY, YESTERDAY, LAST_7_DAYS, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, CUSTOM
}

data class LedgerReportRange(
    val start: LocalDate,
    val endInclusive: LocalDate
) {
    /** Epoch-millis half-open interval [start, end+1day). */
    fun startMillis(zone: ZoneId): Long =
        start.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endMillisExclusive(zone: ZoneId): Long =
        endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val label: String
        get() {
            val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
            return if (start == endInclusive) {
                "Single day: ${start.format(fmt)}"
            } else {
                "${start.format(fmt)} — ${endInclusive.format(fmt)}"
            }
        }
}

object LedgerReportRanges {

    fun resolve(
        period: LedgerReportPeriod,
        anchor: LocalDate,
        customStart: LocalDate? = null,
        customEnd: LocalDate? = null
    ): LedgerReportRange = when (period) {
        LedgerReportPeriod.TODAY -> LedgerReportRange(anchor, anchor)
        LedgerReportPeriod.YESTERDAY -> LedgerReportRange(anchor.minusDays(1), anchor.minusDays(1))
        LedgerReportPeriod.LAST_7_DAYS -> LedgerReportRange(anchor.minusDays(6), anchor)
        LedgerReportPeriod.THIS_WEEK -> {
            val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sunday = monday.plusDays(6)
            LedgerReportRange(monday, sunday)
        }
        LedgerReportPeriod.LAST_WEEK -> {
            val thisMonday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val lastMonday = thisMonday.minusDays(7)
            LedgerReportRange(lastMonday, lastMonday.plusDays(6))
        }
        LedgerReportPeriod.THIS_MONTH -> LedgerReportRange(
            anchor.withDayOfMonth(1),
            anchor.withDayOfMonth(anchor.lengthOfMonth())
        )
        LedgerReportPeriod.LAST_MONTH -> {
            val firstOfThisMonth = anchor.withDayOfMonth(1)
            val lastMonthEnd = firstOfThisMonth.minusDays(1)
            LedgerReportRange(lastMonthEnd.withDayOfMonth(1), lastMonthEnd)
        }
        LedgerReportPeriod.CUSTOM -> {
            val start = customStart ?: anchor.withDayOfMonth(1)
            val end = customEnd ?: anchor
            if (start <= end) LedgerReportRange(start, end) else LedgerReportRange(end, start)
        }
    }
}

/** One row of the report's transaction table. */
data class LedgerReportLine(
    val timestampEpochMillis: Long,
    val isIncome: Boolean,
    /** "Money In" or the category display name. */
    val categoryName: String,
    /** Income source label for money-in rows, blank for expenses. */
    val sourceLabel: String,
    val description: String,
    val paymentMethodLabel: String,
    val provider: String,
    val amountCents: Long,
    /** Running balance of Money In (−) Expenses accumulated up to and including this row. */
    val runningBalanceCents: Long,
    /** Fee bundled with this expense row (zero for money-in rows). */
    val feeCents: Long = 0L,
    /** "SMS" or "Manual" — where the record came from. */
    val sourceTag: String = ""
)

data class LedgerReportBreakdownRow(
    val label: String,
    val spentCents: Long,
    val transactionCount: Int
)

/** One account's balance as shown in a report (current, labelled as such). */
data class ReportAccountBalance(
    val name: String,
    val typeLabel: String,
    val balanceCents: Long
)

/** Unresolved provider-vs-ledger difference (never applied silently). */
data class ReportReconciliationWarning(
    val name: String,
    val reportedCents: Long,
    val ledgerCents: Long,
    val differenceCents: Long
)

/** One transfer inside the range — money movement, not income or expense. */
data class ReportTransferLine(
    val timestampEpochMillis: Long,
    val fromName: String,
    val toName: String,
    val amountCents: Long,
    val feeCents: Long,
    val note: String
)

data class LedgerReportData(
    val range: LedgerReportRange,
    val openingBalanceCents: Long,
    /** Money added within the range (includes refunds & adjustments). */
    val moneyInCents: Long,
    val expensesCents: Long,
    val closingBalanceCents: Long,
    val transactionCount: Int,
    val lines: List<LedgerReportLine>,
    val categoryBreakdown: List<LedgerReportBreakdownRow>,
    val methodBreakdown: List<LedgerReportBreakdownRow>,
    /** Fees debited in the range (real money out, reported separately). */
    val feesCents: Long = 0L,
    /** Subset of money-in that came back as refunds. */
    val refundsCents: Long = 0L,
    /** Transfer volume in the range — excluded from income/expense by design. */
    val transfersCents: Long = 0L,
    /** Fees charged on those transfers (debited from balances). */
    val transferFeesCents: Long = 0L,
    val transfers: List<ReportTransferLine> = emptyList(),
    val smsRowCount: Int = 0,
    val manualRowCount: Int = 0,
    /** Unreviewed detections inside the range — counted, never money. */
    val pendingCount: Int = 0,
    val pendingAmountCents: Long = 0L,
    /** Current per-account balances at generation time (labelled in output). */
    val accountBalances: List<ReportAccountBalance> = emptyList(),
    val reconciliationWarnings: List<ReportReconciliationWarning> = emptyList()
)

/**
 * Pure report builder: it merges expense + income rows for the selected range
 * into a chronological statement without duplicating any transaction. Refunds
 * recorded as money-in ("MoneySource.REFUND") appear once, in the Money In
 * column, never also as an expense reduction.
 */
object LedgerReportBuilder {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    fun build(
        range: LedgerReportRange,
        allExpenses: List<ExpenseEntity>,
        allIncomes: List<IncomeEntity>,
        zone: ZoneId,
        categoryLabel: (String) -> String = { it },
        methodLabel: (String) -> String = { PaymentMethod.resolveId(it).label },
        transfers: List<TransferEntity> = emptyList(),
        pendingDetections: List<SmsDetectionEntity> = emptyList(),
        accountName: (String) -> String = { it },
        accountBalances: List<ReportAccountBalance> = emptyList(),
        reconciliationWarnings: List<ReportReconciliationWarning> = emptyList()
    ): LedgerReportData {
        val startMillis = range.startMillis(zone)
        val endMillis = range.endMillisExclusive(zone)

        val beforeExpenses = allExpenses.filter { it.timestampEpochMillis < startMillis }
        val beforeIncomes = allIncomes.filter { it.timestampEpochMillis < startMillis }
        // Transfer fees before the range already left the pot; include them so
        // the opening figure agrees with the closing one across transfers.
        val opening = balanceCents(beforeIncomes, beforeExpenses) -
            transfers.filter { it.timestampEpochMillis < startMillis }.sumOf { it.feeCents }

        val inRangeExpenses = allExpenses.filter {
            it.timestampEpochMillis in startMillis until endMillis
        }
        val inRangeIncomes = allIncomes.filter {
            it.timestampEpochMillis in startMillis until endMillis
        }

        data class Merged(
            val timestamp: Long,
            val isIncome: Boolean,
            val categoryName: String,
            val sourceLabel: String,
            val description: String,
            val methodLabel: String,
            val provider: String,
            val amountCents: Long,
            val feeCents: Long,
            val sourceTag: String
        )

        val merged = buildList {
            inRangeExpenses.forEach { e ->
                add(
                    Merged(
                        timestamp = e.timestampEpochMillis,
                        isIncome = false,
                        categoryName = categoryLabel(e.categoryId),
                        sourceLabel = "",
                        description = e.title,
                        methodLabel = methodLabel(e.paymentMethod),
                        provider = e.paymentProvider,
                        amountCents = e.amountCents,
                        feeCents = e.feeCents,
                        sourceTag = e.source
                    )
                )
            }
            inRangeIncomes.forEach { i ->
                add(
                    Merged(
                        timestamp = i.timestampEpochMillis,
                        isIncome = true,
                        categoryName = "MONEY IN",
                        sourceLabel = com.kanso.spending.data.model.MoneySource.fromId(i.sourceId).label,
                        description = i.title,
                        methodLabel = methodLabel(i.paymentMethod),
                        provider = i.paymentProvider,
                        amountCents = i.amountCents,
                        feeCents = 0L,
                        sourceTag = i.source
                    )
                )
            }
        }.sortedWith(compareBy({ it.timestamp }, { !it.isIncome }))

        var running = opening
        val lines = merged.map { row ->
            // Fees are debits too: the running balance subtracts principal + fee,
            // while expensesCents keeps them separable in the totals.
            running = if (row.isIncome) {
                running + row.amountCents
            } else {
                running - (row.amountCents + row.feeCents)
            }
            LedgerReportLine(
                timestampEpochMillis = row.timestamp,
                isIncome = row.isIncome,
                categoryName = row.categoryName,
                sourceLabel = row.sourceLabel,
                description = row.description,
                paymentMethodLabel = row.methodLabel,
                provider = row.provider,
                amountCents = row.amountCents,
                runningBalanceCents = running,
                feeCents = row.feeCents,
                sourceTag = row.sourceTag
            )
        }

        val moneyIn = inRangeIncomes.sumOf { it.amountCents }
        val expenses = inRangeExpenses.sumOf { it.amountCents }
        val fees = inRangeExpenses.sumOf { it.feeCents }
        val refunds = inRangeIncomes
            .filter { it.sourceId == com.kanso.spending.data.model.MoneySource.REFUND.id }
            .sumOf { it.amountCents }
        val smsRows = inRangeExpenses.count { it.source == "SMS" } +
            inRangeIncomes.count { it.source == "SMS" }
        val totalRows = inRangeExpenses.size + inRangeIncomes.size

        val inRangeTransfers = transfers.filter { it.timestampEpochMillis in startMillis until endMillis }
        val pendingInRange = pendingDetections.filter {
            it.reviewStatus == "PENDING" && it.timestampEpochMillis in startMillis until endMillis
        }

        val categoryBreakdown = inRangeExpenses
            .groupBy { categoryLabel(it.categoryId) }
            .map { (label, items) ->
                LedgerReportBreakdownRow(label, items.sumOf { it.amountCents }, items.size)
            }
            .sortedByDescending { it.spentCents }

        val methodBreakdown = inRangeExpenses
            .groupBy { methodLabel(it.paymentMethod) }
            .map { (label, items) ->
                LedgerReportBreakdownRow(label, items.sumOf { it.amountCents }, items.size)
            }
            .sortedByDescending { it.spentCents }

        return LedgerReportData(
            range = range,
            openingBalanceCents = opening,
            moneyInCents = moneyIn,
            expensesCents = expenses,
            closingBalanceCents = running - inRangeTransfers.sumOf { it.feeCents },
            transactionCount = totalRows,
            lines = lines,
            categoryBreakdown = categoryBreakdown,
            methodBreakdown = methodBreakdown,
            feesCents = fees,
            refundsCents = refunds,
            transfersCents = inRangeTransfers.sumOf { it.amountCents },
            transferFeesCents = inRangeTransfers.sumOf { it.feeCents },
            transfers = inRangeTransfers.map { t ->
                ReportTransferLine(
                    timestampEpochMillis = t.timestampEpochMillis,
                    fromName = accountName(t.fromAccountId),
                    toName = accountName(t.toAccountId),
                    amountCents = t.amountCents,
                    feeCents = t.feeCents,
                    note = t.note
                )
            },
            smsRowCount = smsRows,
            manualRowCount = totalRows - smsRows,
            pendingCount = pendingInRange.size,
            pendingAmountCents = pendingInRange.sumOf { it.amountCents ?: 0L },
            accountBalances = accountBalances,
            reconciliationWarnings = reconciliationWarnings
        )
    }

    fun balanceCents(incomes: List<IncomeEntity>, expenses: List<ExpenseEntity>): Long =
        incomes.sumOf { it.amountCents } - expenses.sumOf { it.amountCents + it.feeCents }

    fun formatDateLine(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(dateFormatter)

    fun formatTimeLine(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(timeFormatter)
}
