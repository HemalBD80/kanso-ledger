package com.kanso.spending.domain.categorization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CategorySuggesterTest {

    private val userRules = listOf(
        CategoryRule("r1", "star kitchen", "dining"),
        CategoryRule("r2", "green delta", "bills")
    )

    @Test
    fun userRuleWinsOverBuiltin() {
        val s = CategorySuggester.suggest("Payment to Star Kitchen Ltd", userRules)
        assertNotNull(s)
        assertEquals("dining", s!!.categoryId)
        assertEquals("user-rule", s.source)
        assertEquals(95, s.confidence)
    }

    @Test
    fun builtinMatchesRestaurantToDining() {
        val s = CategorySuggester.suggest("Restaurant ABC Dhaka")
        assertEquals("dining", s!!.categoryId)
        assertEquals("builtin", s.source)
    }

    @Test
    fun builtinMatchesTransportKeywords() {
        assertEquals("transit", CategorySuggester.suggest("Uber trip fare")!!.categoryId)
        assertEquals("transit", CategorySuggester.suggest("Pathao bike ride")!!.categoryId)
        assertEquals("transit", CategorySuggester.suggest("fuel pump Bonosree")!!.categoryId)
    }

    @Test
    fun builtinMatchesBillsUtilitiesAndRecharge() {
        assertEquals("bills", CategorySuggester.suggest("DESCO electricity bill")!!.categoryId)
        assertEquals("mobile", CategorySuggester.suggest("GP internet recharge")!!.categoryId)
    }

    @Test
    fun builtinMatchesShoppingGroceriesHealth() {
        assertEquals("shopping", CategorySuggester.suggest("Daraz shopping order")!!.categoryId)
        assertEquals("groceries", CategorySuggester.suggest("Shwapno grocery")!!.categoryId)
        assertEquals("wellness", CategorySuggester.suggest("Square Pharmacy medicine")!!.categoryId)
    }

    @Test
    fun longestUserKeywordWinsSoPhraseBeatsWord() {
        val rules = listOf(
            CategoryRule("a", "pizza", "dining"),
            CategoryRule("b", "pizza delivery", "shopping")
        )
        // "pizza delivery" is present in the text → longer rule should win.
        val s = CategorySuggester.suggest("order pizza delivery please", rules)
        assertEquals("shopping", s!!.categoryId)
    }

    @Test
    fun unknownTextReturnsNullRatherThanGuessing() {
        assertNull(CategorySuggester.suggest("asdfqwerty zzz"))
        assertNull(CategorySuggester.suggest(""))
    }

    @Test
    fun suggestionsRespectAvailableCategoryIds() {
        val s = CategorySuggester.suggest(
            "Restaurant ABC",
            availableCategoryIds = setOf("groceries", "transit")
        )
        // dining not available → builtin unusable; falls back to an available id or null
        if (s != null) {
            assert(s.categoryId in setOf("groceries", "transit"))
        }
    }

    @Test
    fun userRuleWithUnavailableCategoryIsIgnored() {
        val rules = listOf(CategoryRule("x", "star kitchen", "deleted-cat"))
        val s = CategorySuggester.suggest("Star Kitchen", rules, availableCategoryIds = setOf("dining"))
        // rule points at a category that no longer exists → builtin fallback
        assertEquals("dining", s!!.categoryId)
    }

    @Test
    fun fallbackCategoryIsOther() {
        assertEquals("other", CategorySuggester.fallbackCategory())
    }
}
