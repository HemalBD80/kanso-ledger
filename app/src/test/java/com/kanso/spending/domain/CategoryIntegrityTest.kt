package com.kanso.spending.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CategoryIntegrityTest {

    @Test
    fun `default seed covers the requested category set with stable ids`() {
        val defaults = com.kanso.spending.data.repository.SpendingRepository.DEFAULT_CATEGORIES
        val labels = defaults.map { it.displayName }
        assertTrue(labels.contains("Food & Dining"))
        assertTrue(labels.contains("Groceries"))
        assertTrue(labels.contains("Transportation"))
        assertTrue(labels.contains("Shopping"))
        assertTrue(labels.contains("Health"))
        assertTrue(labels.contains("Education"))
        assertTrue(labels.contains("Entertainment"))
        assertTrue(labels.contains("Bills & Utilities"))
        assertTrue(labels.contains("Subscriptions"))
        assertTrue(labels.contains("Other"))
        // Legacy v1.0 ids survive: renaming never changed what's stored in expenses.
        assertTrue(defaults.any { it.id == "wellness" })
        assertTrue(defaults.any { it.id == "leisure" })

        // Ids are unique: an expense row can only be linked to one entry.
        assertEquals(defaults.size, defaults.map { it.id }.toSet().size)
    }

    @Test
    fun `uncategorized archive bucket is part of the defaults and is never custom`() {
        val other = com.kanso.spending.data.repository.SpendingRepository.DEFAULT_CATEGORIES
            .first { it.id == com.kanso.spending.data.local.entity.CategoryEntity.UNCATEGORIZED_ID }
        assertEquals("Other", other.displayName)
        assertTrue(!other.isCustom)
    }

    @Test
    fun `weekly ranges always start on Monday and end on Sunday`() {
        val zone = java.time.ZoneId.systemDefault()
        val zoneUnused = zone // deterministic LocalDate math needs no zone
        for (offset in 0..6) {
            val anchor = LocalDate.of(2026, 10, 12).plusDays(offset.toLong())
            val week = LedgerReportRanges.resolve(LedgerReportPeriod.THIS_WEEK, anchor)
            assertEquals(java.time.DayOfWeek.MONDAY, week.start.dayOfWeek)
            assertEquals(java.time.DayOfWeek.SUNDAY, week.endInclusive.dayOfWeek)
        }
    }
}
