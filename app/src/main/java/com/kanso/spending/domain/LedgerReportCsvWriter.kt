package com.kanso.spending.domain

import java.time.ZoneId

/**
 * Pure CSV renderer for the ledger report. Values are written as decimal major
 * units with 2 places (BDT-friendly) so they open cleanly in any spreadsheet.
 * Deterministic and safe to unit-test on the JVM.
 */
object LedgerReportCsvWriter {

    fun toCsv(report: LedgerReportData, zone: ZoneId): String {
        val sb = StringBuilder()
        sb.appendCsvLine("Kanso Ledger Expense Report")
        sb.appendCsvLine("Period", report.range.label)
        sb.appendCsvLine("Opening Balance (cents)", report.openingBalanceCents.toString())
        sb.appendCsvLine("Money Added (cents)", report.moneyInCents.toString())
        sb.appendCsvLine("Total Expenses (cents)", report.expensesCents.toString())
        sb.appendCsvLine("Closing Balance (cents)", report.closingBalanceCents.toString())
        sb.appendCsvLine("Transaction Count", report.transactionCount.toString())
        sb.appendCsvLine()
        sb.appendCsvLine(
            "Date", "Time", "Type", "Category", "Source", "Description",
            "Payment Method", "Provider", "Amount In (cents)", "Amount Out (cents)", "Balance (cents)"
        )
        report.lines.forEach { line ->
            sb.appendCsvLine(
                LedgerReportBuilder.formatDateLine(line.timestampEpochMillis, zone),
                LedgerReportBuilder.formatTimeLine(line.timestampEpochMillis, zone),
                if (line.isIncome) "MONEY IN" else "EXPENSE",
                line.categoryName,
                line.sourceLabel,
                line.description,
                line.paymentMethodLabel,
                line.provider,
                if (line.isIncome) line.amountCents.toString() else "0",
                if (line.isIncome) "0" else line.amountCents.toString(),
                line.runningBalanceCents.toString()
            )
        }
        sb.appendCsvLine()
        sb.appendCsvLine("Category Breakdown", "Amount (cents)", "Transactions")
        report.categoryBreakdown.forEach { row ->
            sb.appendCsvLine(row.label, row.spentCents.toString(), row.transactionCount.toString())
        }
        sb.appendCsvLine()
        sb.appendCsvLine("Payment Method Breakdown", "Amount (cents)", "Transactions")
        report.methodBreakdown.forEach { row ->
            sb.appendCsvLine(row.label, row.spentCents.toString(), row.transactionCount.toString())
        }
        return sb.toString()
    }

    /** Escapes quotes, commas and newlines the RFC-4180 way. */
    fun csvSafe(field: String): String = field.replace("\"", "\"\"")

    private fun StringBuilder.appendCsvLine(vararg fields: String) {
        append(fields.joinToString(",") { "\"${csvSafe(it)}\"" })
        append("\r\n")
    }
}
