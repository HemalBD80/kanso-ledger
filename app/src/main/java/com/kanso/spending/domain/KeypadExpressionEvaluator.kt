package com.kanso.spending.domain

import kotlin.math.roundToLong

/**
 * Evaluates inline addition/subtraction expressions entered on the tactile numeric keypad
 * (e.g. "14.50+3.20" -> 1770 cents) so users can split or sum receipts without leaving the app.
 */
object KeypadExpressionEvaluator {

    data class EvaluationResult(
        val rawExpression: String,
        val totalCents: Long,
        val hasOperator: Boolean,
        val formattedPreview: String
    )

    fun evaluate(expression: String): EvaluationResult {
        val cleaned = expression.replace(" ", "")
        if (cleaned.isEmpty()) {
            return EvaluationResult(
                rawExpression = "0",
                totalCents = 0L,
                hasOperator = false,
                formattedPreview = "0.00"
            )
        }

        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        for (i in cleaned.indices) {
            val ch = cleaned[i]
            if ((ch == '+' || ch == '-') && i > 0) {
                if (current.isNotEmpty()) {
                    tokens.add(current.toString())
                    current.clear()
                }
                tokens.add(ch.toString())
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        var runningCents = 0L
        var pendingOp = "+"
        var sawOperator = false

        for (token in tokens) {
            if (token == "+" || token == "-") {
                pendingOp = token
                sawOperator = true
            } else {
                val valueCents = parseDecimalToCents(token)
                runningCents = if (pendingOp == "+") {
                    runningCents + valueCents
                } else {
                    runningCents - valueCents
                }
            }
        }

        val clampedCents = runningCents.coerceAtLeast(0L)
        return EvaluationResult(
            rawExpression = cleaned,
            totalCents = clampedCents,
            hasOperator = sawOperator,
            formattedPreview = CurrencyFormatter.formatCentsPlain(clampedCents)
        )
    }

    fun evaluateToCents(expression: String): Long = evaluate(expression).totalCents

    fun containsOperator(expression: String): Boolean = evaluate(expression).hasOperator

    fun appendToken(currentExpression: String, token: String): String = appendKey(currentExpression, token)

    fun backspace(currentExpression: String): String = appendKey(currentExpression, "BACKSPACE")

    fun clear(): String = "0"

    fun appendKey(currentExpression: String, key: String): String {
        val expr = if (currentExpression == "0") "" else currentExpression
        return when (key) {
            "BACKSPACE" -> {
                if (expr.length <= 1) "0" else expr.dropLast(1)
            }
            "CLEAR" -> "0"
            "+", "-" -> {
                if (expr.isEmpty()) "0"
                else if (expr.last() == '+' || expr.last() == '-') {
                    expr.dropLast(1) + key
                } else if (expr.last() == '.') {
                    expr + "0" + key
                } else {
                    expr + key
                }
            }
            "." -> {
                val lastSegment = expr.split('+', '-').lastOrNull().orEmpty()
                if (lastSegment.contains('.')) {
                    currentExpression
                } else if (lastSegment.isEmpty()) {
                    "${expr}0."
                } else {
                    "$expr."
                }
            }
            else -> {
                // Digit 0-9
                val lastSegment = expr.split('+', '-').lastOrNull().orEmpty()
                val decimalIndex = lastSegment.indexOf('.')
                if (decimalIndex != -1 && lastSegment.length - decimalIndex > 2) {
                    // Already has 2 decimal places in current operand
                    currentExpression
                } else if (lastSegment == "0" && key == "0") {
                    currentExpression
                } else if (lastSegment == "0" && key != ".") {
                    expr.dropLast(1) + key
                } else if (expr.length >= 16) {
                    currentExpression
                } else {
                    expr + key
                }
            }
        }
    }

    private fun parseDecimalToCents(token: String): Long {
        val sanitized = token.trimEnd('.')
        if (sanitized.isEmpty()) return 0L
        val value = sanitized.toDoubleOrNull() ?: return 0L
        return (value * 100.0).roundToLong()
    }
}
