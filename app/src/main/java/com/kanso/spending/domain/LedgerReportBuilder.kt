package com.kanso.spending.domain

import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.local.entity.IncomeEntity
import com.kanso.spending.data.model.PaymentMethod
import java.time.DayOfWeek
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
    val runningBalanceCents: Long
)

data class LedgerReportBreakdownRow(
    val label: String,
    val spentCents: Long,
    val transactionCount: Int
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
    val methodBreakdown: List<LedgerReportBreakdownRow>
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
        methodLabel: (String) -> String = { PaymentMethod.resolveId(it).label }
    ): LedgerReportData {
        val startMillis = range.startMillis(zone)
        val endMillis = range.endMillisExclusive(zone)

        val beforeExpenses = allExpenses.filter { it.timestampEpochMillis < startMillis }
        val beforeIncomes = allIncomes.filter { it.timestampEpochMillis < startMillis }
        val opening = balanceCents(beforeIncomes, beforeExpenses)

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
            val amountCents: Long
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
                        amountCents = e.amountCents
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
                        amountCents = i.amountCents
                    )
                )
            }
        }.sortedWith(compareBy({ it.timestamp }, { !it.isIncome }))

        var running = opening
        val lines = merged.map { row ->
            running = if (row.isIncome) running + row.amountCents else running - row.amountCents
            LedgerReportLine(
                timestampEpochMillis = row.timestamp,
                isIncome = row.isIncome,
                categoryName = row.categoryName,
                sourceLabel = row.sourceLabel,
                description = row.description,
                paymentMethodLabel = row.methodLabel,
                provider = row.provider,
                amountCents = row.amountCents,
                runningBalanceCents = running
            )
        }

        val moneyIn = inRangeIncomes.sumOf { it.amountCents }
        val expenses = inRangeExpenses.sumOf { it.amountCents }

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
            closingBalanceCents = running,
            transactionCount = inRangeExpenses.size + inRangeIncomes.size,
            lines = lines,
            categoryBreakdown = categoryBreakdown,
            methodBreakdown = methodBreakdown
        )
    }

    fun balanceCents(incomes: List<IncomeEntity>, expenses: List<ExpenseEntity>): Long =
        incomes.sumOf { it.amountCents } - expenses.sumOf { it.amountCents }

    fun formatDateLine(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(dateFormatter)

    fun formatTimeLine(millis: Long, zone: ZoneId): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).format(timeFormatter)
}
