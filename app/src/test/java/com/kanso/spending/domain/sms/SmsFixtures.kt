package com.kanso.spending.domain.sms

/**
 * Representative fictional SMS fixtures. These messages are invented for
 * testing — they must never contain real private message content.
 */
object SmsFixtures {

    // ---- bKash -----------------------------------------------------------
    const val BKASH_SENT =
        "You have sent BDT 1,250.00 to 01712345678. Fee BDT 5.00. New Balance BDT 4,320.50. TrxID bK6P9Q2R. 15/10/26 14:22"

    const val BKASH_RECEIVED =
        "You have received BDT 3,000.00 from 01812345678. New Balance BDT 7,450.25. TrxID rX9A2B3C. 14/10/26 09:05"

    const val BKASH_CASHOUT =
        "Cash Out of BDT 5,000.00 from agent 01715123456. Fee BDT 10.50. Available Balance BDT 2,440.00. TrxID cO1D2E3F. 13/10/26 18:40"

    const val BKASH_PROMO =
        "Get 20% cashback on Send Money! Offer valid till 30 Oct. T&C apply. Dial *247#"

    const val BKASH_OTP =
        "Your OTP is 482913. Do not share it with anyone. Verification code for bKash login."

    // ---- Nagad -----------------------------------------------------------
    const val NAGAD_SENT =
        "Tk 500.00 has been sent to 01612345678. New Avl Bal Tk 2,100.00. TrxID: NG4T7Y8U. 12/10/26 11:15"

    const val NAGAD_RECEIVED =
        "Tk 1,200.00 received from 01512345678. Avl Bal Tk 3,300.00. TrxID: NG5V6W7X. 11/10/26 16:20"

    // ---- Rocket (DBBL) ---------------------------------------------------
    const val ROCKET_RECEIVED =
        "You have received BDT 3,000.00 from 01912345678. New balance BDT 7,450.25. TrxID: RK9X2Y3Z. 10/10/26 10:00"

    const val ROCKET_SENT =
        "You have sent BDT 750.00 to 01987654321. New balance BDT 6,700.25. TrxID: RK1A2B3C. 09/10/26 12:30"

    // ---- Bank ------------------------------------------------------------
    const val BANK_DEBIT_PURCHASE =
        "BDT 850.00 spent on Debit Card *4421 at AGORA on 15/10/26 14:22. Avl Bal BDT 12,340.11. Ref: TXN442101"

    const val BANK_SALARY_CREDIT =
        "BDT 25,000.00 credited to A/c ***4512 as SALARY on 01/10/26 09:00. Bal BDT 58,120.90. Ref: CR900221"

    const val BANK_ATM_WITHDRAWAL =
        "BDT 5,000.00 withdrawn from A/c ***4512 at ATM DHK 0451 on 08/10/26 17:05. Bal BDT 53,120.90. Ref: ATM771001"

    const val BANK_FEE =
        "Account Maintenance Fee BDT 120.00 debited from A/c ***4512 on 05/10/26 00:05. Bal BDT 58,120.90. Ref: FEE5501"

    const val BANK_BALANCE_INQUIRY =
        "Your A/c ***4512 balance is BDT 53,120.90 as of 15/10/26. Reply B for details."

    const val BANK_CASH_DEPOSIT =
        "Cash Deposit BDT 10,000.00 to A/c ***4512 on 07/10/26 10:45. Bal BDT 63,120.90. Ref: DP11002"

    // ---- Card ------------------------------------------------------------
    const val CARD_PURCHASE =
        "BDT 2,300.00 used for Credit Card *7788 at STAR KITCHEN on 14/10/26 20:10. Available Limit BDT 45,000.00. Ref: TXN9981"

    const val CARD_REFUND =
        "Credit Card *7788 refund of BDT 1,150.00 from SHOPMART on 13/10/26 12:00. Available Limit BDT 46,150.00. Ref: RFD778812"

    const val CARD_BALANCE_ONLY =
        "Credit Card *7788 Available Limit is BDT 45,000.00. Reply BAL to know more."

    // ---- Recharge, bills & data packages ---------------------------------
    const val BKASH_RECHARGE =
        "You have paid BDT 150.00 for your mobile recharge. New Balance BDT 1,050.00. TrxID RC5T6U7V. 16/10/26 10:20"

    const val BANK_ELECTRICITY_BILL =
        "Electricity bill payment of BDT 1,250.00 from A/c ***4512 on 12/10/26 09:30. Avl Bal BDT 20,000.00. Ref: BILL7712"

    const val GENERIC_INTERNET_PACKAGE =
        "You have bought an internet package of BDT 349.00. TrxID GP882114. 10/10/26 20:00"

    // ---- Ambiguous / unfamiliar ------------------------------------------
    const val AMBIGUOUS_UNFAMILIAR =
        "Money movement of BDT for your transaction has been processed today. Contact branch for detail."

    // ---- v2.4 real-world formats (supplied examples + extra fictional) ----
    /** Example A — bank debit with masked account and a 12-hour timestamp. */
    const val TRUST_DEBIT =
        "TRUST MONEY Txn\nTK 206.00 DEBIT\nAC No 033***558\n16/10/2026 12:17 AM\nBalance TK 9528.95"

    /** Example B — cashback credit that opens with "Congratulations!". */
    const val BKASH_CASHBACK =
        "Congratulations! You have received Cashback Tk 25.00. Balance Tk 128.58. TrxID DJE9WXYIXT at 14/10/2026 17:03. Thank you for availing bKash Reward Points."

    /** Example C — mobile recharge with a leading-zero "Transaction ID". */
    const val MOBILE_RECHARGE =
        "Transaction ID is 000319000740 . 8801513925313 has recharged your account with 120.00 Taka."

    /** A cashback with no TrxID is indistinguishable from a promo — still filtered. */
    const val CASHBACK_PROMO =
        "Congratulations! Get 25% cashback on every recharge. Offer valid till 30 Nov. T&C apply."

    /** A reversal of a prior card transaction — never new income. */
    const val CARD_REVERSAL =
        "Reversal of BDT 1,150.00 on Credit Card *7788 from SHOPMART. Available Limit BDT 46,150.00. Ref: RVSL99812. 13/10/26 12:30"

    /** A self-transfer between the user's own wallet and bank account. */
    const val WALLET_TRANSFER_SELF =
        "You have transferred BDT 2,000.00 to your bank A/c ***4512. New Balance BDT 5,320.50. TrxID TRX1122AB. 15/10/26 09:00"

    /** A balance-only notice: carries money text but never a transaction. */
    const val BKASH_BALANCE_NOTICE =
        "Your bKash Account balance is Tk 1,500.00 as of 15/10/26 09:00. Dial *247# for details."

    /** A standalone bank fee — the fee IS the debit. */
    const val BANK_CREDIT_SALARY =
        "BDT 42,000.00 credited to A/c ***4512 as SALARY on 02/10/26 09:30. Bal BDT 60,120.90. Ref: CR900999"

    /** Parse helper: fixed received time so fingerprints are deterministic. */
    fun parse(fixture: String, sender: String = "bKash", receivedAt: Long = 1_760_000_000_000L): SmsParseResult =
        SmsParser.parse(sender, fixture, receivedAt)
}
