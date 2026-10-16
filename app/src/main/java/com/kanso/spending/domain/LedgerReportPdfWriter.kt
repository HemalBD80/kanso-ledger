package com.kanso.spending.domain

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream

/**
 * Print-friendly PDF renderer built entirely on android.graphics.pdf — 100%
 * on-device, no network. Wraps rows across A4 pages and finishes with the
 * category and payment-method breakdown tables.
 */
object LedgerReportPdfWriter {

    private const val PAGE_WIDTH = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40
    private const val TOP_Y = 56f
    private const val LINE_H = 18f

    fun toPdfBytes(report: LedgerReportData, currencySymbol: String, rangeLabel: String): ByteArray {
        val doc = PdfDocument()

        val titlePaint = Paint().apply {
            isAntiAlias = true
            textSize = 20f
            isFakeBoldText = true
            color = Color.BLACK
        }
        val headingPaint = Paint(titlePaint).apply { textSize = 12f }
        val normalPaint = Paint(titlePaint).apply { textSize = 10f; isFakeBoldText = false; color = Color.DKGRAY }
        val boldPaint = Paint(normalPaint).apply { isFakeBoldText = true; color = Color.BLACK }
        val mutedPaint = Paint(normalPaint).apply { color = Color.GRAY }

        var pageNo = 1
        var page = doc.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNo).create()
        )
        var canvas = page.canvas
        var y = TOP_Y

        fun newPageIfNeeded(neededF: Float = LINE_H) {
            if (y + neededF > PAGE_HEIGHT - MARGIN) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNo).create()
                )
                canvas = page.canvas
                y = TOP_Y
            }
        }

        fun text(paint: Paint, str: String, x: Float = MARGIN.toFloat()) {
            newPageIfNeeded()
            canvas.drawText(str, x, y, paint)
            y += paint.textSize + 4f
        }

        text(titlePaint, "Kanso Ledger — Expense Report")
        text(mutedPaint, "Period: $rangeLabel")
        y += 8f

        text(headingPaint, "SUMMARY")
        text(normalPaint, "Opening balance: $currencySymbol${report.openingBalanceCents.toMajor()}")
        text(normalPaint, "Money added: $currencySymbol${report.moneyInCents.toMajor()}")
        text(normalPaint, "Total expenses: $currencySymbol${report.expensesCents.toMajor()}")
        text(boldPaint, "Closing balance: $currencySymbol${report.closingBalanceCents.toMajor()}")
        text(normalPaint, "Transaction count: ${report.transactionCount}")
        y += 8f

        text(headingPaint, "TRANSACTIONS")
        // Column header
        y += 2f
        text(boldPaint, "Date      Time  Type      Description          Method        Amount")
        report.lines.forEach { line ->
            val typePad = if (line.isIncome) "MONEY IN " else "EXPENSE  "
            val amount = (if (line.isIncome) "+" else "-") + currencySymbol + line.amountCents.toMajor()
            val date = LedgerReportBuilder.formatDateLine(line.timestampEpochMillis, java.time.ZoneId.systemDefault())
            val time = LedgerReportBuilder.formatTimeLine(line.timestampEpochMillis, java.time.ZoneId.systemDefault())
            val categoryTag = if (line.isIncome) line.sourceLabel else line.categoryName
            text(
                normalPaint,
                "${date.take(10)} ${time} $typePad ${truncate(line.description, 22)} ${truncate(categoryTag, 10)} ${truncate(line.paymentMethodLabel, 12)} $amount"
            )
        }
        y += 8f

        if (report.categoryBreakdown.isNotEmpty()) {
            newPageIfNeeded(60f)
            text(headingPaint, "CATEGORY BREAKDOWN")
            report.categoryBreakdown.forEach { row ->
                text(normalPaint, "• ${truncate(row.label, 26)}  $currencySymbol${row.spentCents.toMajor()}  (${row.transactionCount})")
            }
            y += 8f
        }

        if (report.methodBreakdown.isNotEmpty()) {
            newPageIfNeeded(60f)
            text(headingPaint, "PAYMENT METHOD BREAKDOWN")
            report.methodBreakdown.forEach { row ->
                text(normalPaint, "• ${truncate(row.label, 26)}  $currencySymbol${row.spentCents.toMajor()}  (${row.transactionCount})")
            }
        }

        doc.finishPage(page)

        val out = ByteArrayOutputStream()
        try {
            doc.writeTo(out)
        } finally {
            doc.close()
        }
        return out.toByteArray()
    }

    private fun truncate(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max - 1) + "…"

    /** Minor units to a fixed 2-decimal major-unit string, e.g. 1250 -> "12.50". */
    fun Long.toMajor(): String {
        val negative = this < 0
        val abs = kotlin.math.abs(this)
        val whole = abs / 100
        val frac = abs % 100
        return (if (negative) "-" else "") + "$whole.${frac.toString().padStart(2, '0')}"
    }
}
