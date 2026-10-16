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
        sb.appendCsvLine("  of which Refunds (cents)", report.refundsCents.toString())
        sb.appendCsvLine("Total Expenses (cents)", report.expensesCents.toString())
        sb.appendCsvLine("Fees (cents)", report.feesCents.toString())
        sb.appendCsvLine("Closing Balance (cents)", report.closingBalanceCents.toString())
        sb.appendCsvLine("Transaction Count", report.transactionCount.toString())
        sb.appendCsvLine("Transfers in period (cents, excluded from income/expense)", report.transfersCents.toString())
        sb.appendCsvLine("Transfer fees deducted from balances (cents)", report.transferFeesCents.toString())
        sb.appendCsvLine("Records from SMS (count)", report.smsRowCount.toString())
        sb.appendCsvLine("Records entered manually (count)", report.manualRowCount.toString())
        if (report.pendingCount > 0) {
            sb.appendCsvLine(
                "Unreviewed detections in period (NOT counted anywhere)",
                "${report.pendingCount} detection(s), ${report.pendingAmountCents} cents potential"
            )
        }
        sb.appendCsvLine()
        sb.appendCsvLine(
            "Date", "Time", "Type", "Category", "Source", "Description",
            "Payment Method", "Provider", "Record Origin",
            "Amount In (cents)", "Amount Out (cents)", "Fee (cents)", "Balance (cents)"
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
                line.sourceTag.ifBlank { "Manual" },
                if (line.isIncome) line.amountCents.toString() else "0",
                if (line.isIncome) "0" else line.amountCents.toString(),
                if (line.isIncome) "0" else line.feeCents.toString(),
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
        if (report.transfers.isNotEmpty()) {
            sb.appendCsvLine()
            sb.appendCsvLine(
                "Transfers (moved between accounts — not income or expense)",
                "From", "To", "Amount (cents)", "Fee (cents)", "Note"
            )
            report.transfers.forEach { t ->
                sb.appendCsvLine(
                    LedgerReportBuilder.formatDateLine(t.timestampEpochMillis, zone),
                    t.fromName,
                    t.toName,
                    t.amountCents.toString(),
                    t.feeCents.toString(),
                    t.note
                )
            }
        }
        if (report.accountBalances.isNotEmpty()) {
            sb.appendCsvLine()
            sb.appendCsvLine("Current Account Balances (as generated)", "Type", "Balance (cents)")
            report.accountBalances.forEach { acc ->
                sb.appendCsvLine(acc.name, acc.typeLabel, acc.balanceCents.toString())
            }
        }
        if (report.reconciliationWarnings.isNotEmpty()) {
            sb.appendCsvLine()
            sb.appendCsvLine(
                "Unresolved Reconciliation Differences (ledger unchanged)",
                "Reported (cents)", "Ledger (cents)", "Difference (cents)"
            )
            report.reconciliationWarnings.forEach { w ->
                sb.appendCsvLine(
                    w.name,
                    w.reportedCents.toString(),
                    w.ledgerCents.toString(),
                    w.differenceCents.toString()
                )
            }
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
