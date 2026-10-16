package com.kanso.spending.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadExpressionEvaluatorTest {

    @Test
    fun `evaluates simple decimal and integer amounts to cents`() {
        assertEquals(1850L, KeypadExpressionEvaluator.evaluateToCents("18.50"))
        assertEquals(4200L, KeypadExpressionEvaluator.evaluateToCents("42"))
        assertEquals(0L, KeypadExpressionEvaluator.evaluateToCents("0"))
    }

    @Test
    fun `evaluates inline addition and subtraction expressions accurately`() {
        assertEquals(1770L, KeypadExpressionEvaluator.evaluateToCents("14.50+3.20"))
        assertEquals(925L, KeypadExpressionEvaluator.evaluateToCents("12.00-2.75"))
        assertTrue(KeypadExpressionEvaluator.containsOperator("14.50+3.20"))
    }

    @Test
    fun `prevents duplicate decimal points in same number segment`() {
        val step1 = KeypadExpressionEvaluator.appendToken("12.3", ".")
        assertEquals("12.3", step1)

        val step2 = KeypadExpressionEvaluator.appendToken("12.34", "5")
        assertEquals("12.34", step2) // max 2 decimal places
    }
}
