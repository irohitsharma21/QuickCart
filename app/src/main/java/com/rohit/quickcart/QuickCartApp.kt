package com.rohit.quickcart

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.rohit.quickcart.data.sync.OrderSyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * @HiltAndroidApp generates the app-wide dependency container.
 * Implementing Configuration.Provider lets WorkManager build workers through Hilt,
 * so OrderSyncWorker can have the repository injected.
 */
@HiltAndroidApp
class QuickCartApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var orderSyncScheduler: OrderSyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Flush any orders that were placed offline in an earlier session.
        orderSyncScheduler.schedule()
    }
}
