package com.kanso.spending

import android.app.Application
import com.kanso.spending.data.local.KansoDatabase
import com.kanso.spending.data.repository.SpendingRepository

/**
 * Application singleton providing lazy access to the on-device SQLite Room database
 * and [SpendingRepository] with zero network dependencies.
 */
class KansoApplication : Application() {

    val database: KansoDatabase by lazy {
        KansoDatabase.getInstance(this)
    }

    val repository: SpendingRepository by lazy {
        SpendingRepository(database.spendingDao(), this)
    }
}
