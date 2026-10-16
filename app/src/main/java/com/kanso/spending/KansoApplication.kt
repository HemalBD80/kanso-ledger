package com.kanso.spending

import android.app.Application
import com.kanso.spending.data.local.KansoDatabase
import com.kanso.spending.data.repository.SpendingRepository

/**
 * Application singleton providing lazy access to the on-device SQLite Room database
 * and [SpendingRepository]. The ledger itself has no network dependency; the only
 * network code in the app is the optional, off-by-default AI-assisted parsing
 * feature (see AiSettingsStore), which is gated off until the user enables,
 * consents to, and configures it.
 */
class KansoApplication : Application() {

    val database: KansoDatabase by lazy {
        KansoDatabase.getInstance(this)
    }

    val repository: SpendingRepository by lazy {
        SpendingRepository(database.spendingDao(), this)
    }
}
