package com.kanso.spending.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

object CurrencyFormatter {

    private val symbols = DecimalFormatSymbols(Locale.US)
    private val standardFormat = DecimalFormat("#,##0.00", symbols)
    private val wholeFormat = DecimalFormat("#,##0", symbols)

    fun formatCents(amountCents: Long, currencySymbol: String = "\u09F3", showPlusForPositive: Boolean = false): String {
        val sign = when {
            amountCents < 0L -> "-"
            showPlusForPositive && amountCents > 0L -> "+"
            else -> ""
        }
        val absValue = abs(amountCents) / 100.0
        return "$sign$currencySymbol${standardFormat.format(absValue)}"
    }

    fun formatCentsCompact(amountCents: Long, currencySymbol: String = "\u09F3"): String {
        val sign = if (amountCents < 0L) "-" else ""
        val absCents = abs(amountCents)
        return if (absCents % 100L == 0L) {
            "$sign$currencySymbol${wholeFormat.format(absCents / 100L)}"
        } else {
            "$sign$currencySymbol${standardFormat.format(absCents / 100.0)}"
        }
    }

    fun formatCentsPlain(amountCents: Long): String {
        val absValue = abs(amountCents) / 100.0
        return standardFormat.format(absValue)
    }
}
