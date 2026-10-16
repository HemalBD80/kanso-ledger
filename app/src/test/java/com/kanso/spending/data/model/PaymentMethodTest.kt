package com.kanso.spending.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentMethodTest {

    @Test
    fun `all required Bangladesh-friendly methods exist`() {
        val ids = PaymentMethod.entries.map { it.id }
        assertEquals(listOf("cash", "debit", "credit", "wallet", "transfer", "other"), ids)
    }

    @Test
    fun `mobile wallet shows bKash Nagad Rocket hint`() {
        assertEquals(
            "Mobile Wallet (bKash / Nagad / Rocket)",
            PaymentMethod.MOBILE_WALLET.label
        )
    }

    @Test
    fun `legacy card id from v1 resolves to debit card`() {
        assertEquals(PaymentMethod.DEBIT_CARD, PaymentMethod.resolveId("card"))
        assertEquals(PaymentMethod.DEBIT_CARD, PaymentMethod.resolveId("debit"))
        assertEquals(PaymentMethod.CREDIT_CARD, PaymentMethod.resolveId("credit"))
        assertEquals(PaymentMethod.MOBILE_WALLET, PaymentMethod.resolveId("wallet"))
        assertEquals(PaymentMethod.BANK_TRANSFER, PaymentMethod.resolveId("transfer"))
    }

    @Test
    fun `unknown ids never crash and resolve to other`() {
        assertEquals(PaymentMethod.OTHER, PaymentMethod.resolveId("whatever_new_method"))
        assertEquals(PaymentMethod.OTHER, PaymentMethod.resolveId(""))
    }
}
