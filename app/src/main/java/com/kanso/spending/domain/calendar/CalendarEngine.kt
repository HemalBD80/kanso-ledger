package com.kanso.spending.domain.calendar

import java.util.Calendar

/**
 * Daily ledger facts for a financial calendar. Totals count confirmed
 * activity only; pending detections are shown as a count, not as money.
 */
data class CalendarDay(
    val epochDay: Long,
    val incomingCents: Long,
    val outgoingCents: Long,
    val transactionCount: Int,
    val pendingCount: Int = 0
) {
    val netCents: Long get() = incomingCents - outgoingCents
}

data class CalendarSummary(
    val days: Map<Long, CalendarDay>,
    val weekIncomingCents: Long,
    val weekOutgoingCents: Long,
    val weekCount: Int,
    val monthIncomingCents: Long,
    val monthOutgoingCents: Long,
    val monthCount: Int
) {
    fun day(epochDay: Long): CalendarDay? = days[epochDay]
    /** ISO-style epoch day for a yyyy-MM-dd timestamp (UTC-safe via millis math). */
    fun dayOfTimestamp(millis: Long): Long = millis / 86_400_000L
}

object CalendarEngine {

    fun epochDay(year: Int, month: Int /*1-12*/, day: Int): Long {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis / 86_400_000L
    }

    fun partsOf(epochDay: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        c.timeInMillis = epochDay * 86_400_000L
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /**
     * Build the calendar from raw ledger rows.
     * @param incomingRows (timestampMillis, amountCents) money in
     * @param outgoingRows (timestampMillis, amountCents) money out incl. fees
     * @param pendingTimestamps timestamps of unreviewed detections
     */
    fun build(
        incomingRows: List<Pair<Long, Long>>,
        outgoingRows: List<Pair<Long, Long>>,
        pendingTimestamps: List<Long>,
        monthYear: Int,          // yyyy
        month: Int               // 1-12
    ): CalendarSummary {
        val inDays = mutableMapOf<Long, Long>()
        val outDays = mutableMapOf<Long, Long>()
        val countDays = mutableMapOf<Long, Int>()
        val pendingDays = mutableMapOf<Long, Int>()

        for ((ts, amt) in incomingRows) {
            val d = ts / 86_400_000L
            inDays[d] = (inDays[d] ?: 0L) + amt
            countDays[d] = (countDays[d] ?: 0) + 1
        }
        for ((ts, amt) in outgoingRows) {
            val d = ts / 86_400_000L
            outDays[d] = (outDays[d] ?: 0L) + amt
            countDays[d] = (countDays[d] ?: 0) + 1
        }
        for (ts in pendingTimestamps) {
            val d = ts / 86_400_000L
            pendingDays[d] = (pendingDays[d] ?: 0) + 1
        }

        val keys = (inDays.keys + outDays.keys + countDays.keys + pendingDays.keys)
        val days = keys.associateWith { d ->
            CalendarDay(
                epochDay = d,
                incomingCents = inDays[d] ?: 0L,
                outgoingCents = outDays[d] ?: 0L,
                transactionCount = countDays[d] ?: 0,
                pendingCount = pendingDays[d] ?: 0
            )
        }

        // Month window for the requested month.
        val monthStart = epochDay(monthYear, month, 1)
        val nextMonth = if (month == 12) epochDay(monthYear + 1, 1, 1) else epochDay(monthYear, month + 1, 1)
        var mIn = 0L; var mOut = 0L; var mCount = 0
        for ((d, day) in days) {
            if (d >= monthStart && d < nextMonth) {
                mIn += day.incomingCents; mOut += day.outgoingCents; mCount += day.transactionCount
            }
        }

        // "This week": current 7-day window ending today (local time).
        val today = System.currentTimeMillis() / 86_400_000L
        val weekStart = today - 6
        var wIn = 0L; var wOut = 0L; var wCount = 0
        for ((d, day) in days) {
            if (d in weekStart..today) {
                wIn += day.incomingCents; wOut += day.outgoingCents; wCount += day.transactionCount
            }
        }

        return CalendarSummary(days, wIn, wOut, wCount, mIn, mOut, mCount)
    }

    /** Days in a month, 1-based. */
    fun daysInMonth(year: Int, month: Int): Int {
        val c = Calendar.getInstance()
        c.set(year, month - 1, 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** Weekday index (Mon=0..Sun=6) for the 1st of a month — for grid layout. */
    fun firstWeekdayIndex(year: Int, month: Int): Int {
        val c = Calendar.getInstance()
        c.set(year, month - 1, 1)
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Calendar.SUNDAY=1 → 5
    }
}
