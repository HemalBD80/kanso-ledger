package com.kanso.spending.data.repository

import com.kanso.spending.data.local.entity.CategoryBudgetEntity
import com.kanso.spending.data.local.entity.ExpenseEntity
import com.kanso.spending.data.model.PaymentMethod
import com.kanso.spending.data.model.SpendingCategory
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object SampleLedgerSeeder {

    fun buildSampleExpenses(
        targetMonth: YearMonth,
        today: LocalDate = LocalDate.now()
    ): List<ExpenseEntity> {
        val zone = ZoneId.systemDefault()
        val baseDate = if (YearMonth.from(today) == targetMonth) {
            today
        } else {
            targetMonth.atDay(minOf(16, targetMonth.lengthOfMonth()))
        }

        fun dayMillis(daysAgo: Long, hour: Int, minute: Int): Long {
            val date = baseDate.minusDays(daysAgo).let {
                if (YearMonth.from(it) == targetMonth) it else targetMonth.atDay(1)
            }
            return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        }

        return listOf(
            ExpenseEntity(
                title = "Blue Bottle Espresso & Cortado",
                amountCents = 1150L,
                categoryId = SpendingCategory.DINING.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(0, 9, 15),
                note = "Morning pour-over before studio review"
            ),
            ExpenseEntity(
                title = "Whole Foods Organic Market",
                amountCents = 8420L,
                categoryId = SpendingCategory.GROCERIES.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(0, 18, 40),
                note = "Weekly produce, olive oil, sourdough"
            ),
            ExpenseEntity(
                title = "City Metro Pass Reload",
                amountCents = 3200L,
                categoryId = SpendingCategory.TRANSIT.id,
                paymentMethod = PaymentMethod.WALLET.id,
                timestampEpochMillis = dayMillis(1, 8, 30),
                note = "Commute transit card top-up",
                isRecurring = true
            ),
            ExpenseEntity(
                title = "Ramen Nagi Dinner",
                amountCents = 2890L,
                categoryId = SpendingCategory.DINING.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(1, 20, 10),
                note = "Black king ramen + gyoza"
            ),
            ExpenseEntity(
                title = "Municipal Fiber Internet",
                amountCents = 6500L,
                categoryId = SpendingCategory.HOUSING.id,
                paymentMethod = PaymentMethod.TRANSFER.id,
                timestampEpochMillis = dayMillis(2, 10, 0),
                note = "Monthly gigabit home fiber",
                isRecurring = true
            ),
            ExpenseEntity(
                title = "Kinokuniya Design Monograph",
                amountCents = 4600L,
                categoryId = SpendingCategory.LEISURE.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(3, 15, 25),
                note = "Swiss grid typography archive book"
            ),
            ExpenseEntity(
                title = "Trader Joe's Pantry Run",
                amountCents = 5340L,
                categoryId = SpendingCategory.GROCERIES.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(4, 17, 50),
                note = "Coffee beans, walnuts, sparkling water"
            ),
            ExpenseEntity(
                title = "Apothecary Vitamin D & Magnesium",
                amountCents = 2450L,
                categoryId = SpendingCategory.WELLNESS.id,
                paymentMethod = PaymentMethod.CASH.id,
                timestampEpochMillis = dayMillis(5, 13, 15),
                note = "Restock daily supplements"
            ),
            ExpenseEntity(
                title = "Uniqlo U Heavyweight Tee",
                amountCents = 3990L,
                categoryId = SpendingCategory.SHOPPING.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(6, 16, 5),
                note = "Natural unbleached cotton"
            ),
            ExpenseEntity(
                title = "Cloud Storage & Music Plan",
                amountCents = 1999L,
                categoryId = SpendingCategory.SUBSCRIPTIONS.id,
                paymentMethod = PaymentMethod.CARD.id,
                timestampEpochMillis = dayMillis(7, 11, 0),
                note = "Monthly digital billing",
                isRecurring = true
            )
        )
    }

    fun buildDefaultCategoryBudgets(targetMonth: YearMonth): List<CategoryBudgetEntity> {
        val key = targetMonth.toString()
        return listOf(
            CategoryBudgetEntity(key, SpendingCategory.DINING.id, 35_000L),
            CategoryBudgetEntity(key, SpendingCategory.GROCERIES.id, 45_000L),
            CategoryBudgetEntity(key, SpendingCategory.HOUSING.id, 50_000L),
            CategoryBudgetEntity(key, SpendingCategory.TRANSIT.id, 18_000L),
            CategoryBudgetEntity(key, SpendingCategory.SHOPPING.id, 20_000L)
        )
    }
}
