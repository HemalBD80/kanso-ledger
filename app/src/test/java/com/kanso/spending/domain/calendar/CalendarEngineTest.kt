package com.kanso.spending.domain.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEngineTest {

    private val day = 86_400_000L

    private fun ts(year: Int, month: Int, d: Int, hour: Int = 10): Long {
        val c = java.util.Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, d, hour, 0, 0)
        return c.timeInMillis
    }

    @Test
    fun dayTotalsAggregateIncomingOutgoingAndCounts() {
        val incoming = listOf(
            ts(2026, 10, 5) to 100_000L,
            ts(2026, 10, 5) to 50_000L,     // same day
            ts(2026, 10, 7) to 25_000L
        )
        val outgoing = listOf(
            ts(2026, 10, 5, 14) to 30_000L
        )
        val summary = CalendarEngine.build(incoming, outgoing, emptyList(), 2026, 10)

        val d5 = summary.day(CalendarEngine.epochDay(2026, 10, 5))!!
        assertEquals(150_000L, d5.incomingCents)
        assertEquals(30_000L, d5.outgoingCents)
        assertEquals(3, d5.transactionCount)
        assertEquals(120_000L, d5.netCents)

        val d7 = summary.day(CalendarEngine.epochDay(2026, 10, 7))!!
        assertEquals(1, d7.transactionCount)
        assertTrue(d7.netCents > 0)

        assertEquals(2, summary.days.size)     // only days with activity
        assertEquals(175_000L, summary.days.values.sumOf { it.incomingCents })
    }

    @Test
    fun daysWithNoActivityAreAbsentFromMap() {
        val summary = CalendarEngine.build(emptyList(), emptyList(), emptyList(), 2026, 10)
        assertTrue(summary.days.isEmpty())
        assertTrue(summary.day(CalendarEngine.epochDay(2026, 10, 15)) == null)
    }

    @Test
    fun pendingCountIsTrackedSeparatelyFromMoney() {
        val pending = listOf(ts(2026, 10, 9), ts(2026, 10, 9), ts(2026, 10, 10))
        val summary = CalendarEngine.build(emptyList(), emptyList(), pending, 2026, 10)
        val d9 = summary.day(CalendarEngine.epochDay(2026, 10, 9))!!
        assertEquals(2, d9.pendingCount)
        assertEquals(0L, d9.incomingCents)          // unreviewed ≠ money
        assertEquals(0, d9.transactionCount)
    }

    @Test
    fun monthSummaryOnlyCountsRequestedMonth() {
        val incoming = listOf(
            ts(2026, 9, 30) to 999_000L,   // outside
            ts(2026, 10, 1) to 10_000L,
            ts(2026, 10, 31) to 20_000L,
            ts(2026, 11, 1) to 888_000L    // outside
        )
        val summary = CalendarEngine.build(incoming, emptyList(), emptyList(), 2026, 10)
        assertEquals(30_000L, summary.monthIncomingCents)
        assertEquals(2, summary.monthCount)
    }

    @Test
    fun weekSummaryCoversRollingSevenDays() {
        val today = System.currentTimeMillis() / day
        val incoming = listOf(
            (today * day) to 5_000L,                 // today (in window)
            ((today - 3) * day) to 7_000L,           // 3 days ago (in window)
            ((today - 10) * day) to 9_000L           // out of window
        )
        val summary = CalendarEngine.build(incoming, emptyList(), emptyList(), 2026, 10)
        assertEquals(12_000L, summary.weekIncomingCents)
        assertEquals(2, summary.weekCount)
    }

    @Test
    fun epochDayRoundTripsToYearMonthDay() {
        val e = CalendarEngine.epochDay(2026, 10, 16)
        val (y, m, d) = CalendarEngine.partsOf(e)
        assertEquals(2026, y); assertEquals(10, m); assertEquals(16, d)
    }

    @Test
    fun daysInMonthAndGridOffset() {
        assertEquals(31, CalendarEngine.daysInMonth(2026, 10))
        assertEquals(28, CalendarEngine.daysInMonth(2026, 2))
        assertEquals(30, CalendarEngine.daysInMonth(2026, 4))
        // 1 Oct 2026 is a Thursday → Mon-based index 3.
        assertEquals(3, CalendarEngine.firstWeekdayIndex(2026, 10))
        // 1 Sep 2026 is a Tuesday → index 1.
        assertEquals(1, CalendarEngine.firstWeekdayIndex(2026, 9))
    }
}
