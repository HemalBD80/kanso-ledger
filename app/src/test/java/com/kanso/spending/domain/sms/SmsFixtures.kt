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

    // ---- Ambiguous / unfamiliar ------------------------------------------
    const val AMBIGUOUS_UNFAMILIAR =
        "Money movement of BDT for your transaction has been processed today. Contact branch for detail."

    /** Parse helper: fixed received time so fingerprints are deterministic. */
    fun parse(fixture: String, sender: String = "bKash", receivedAt: Long = 1_760_000_000_000L): SmsParseResult =
        SmsParser.parse(sender, fixture, receivedAt)
}
