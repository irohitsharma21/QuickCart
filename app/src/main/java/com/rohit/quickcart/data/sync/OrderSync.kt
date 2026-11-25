package com.rohit.quickcart.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.data.repository.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface OrderSyncScheduler {
    fun schedule()
}

/**
 * Why WorkManager and not a plain coroutine? A coroutine dies with the process. WorkManager
 * persists the job, waits for the network constraint, retries with backoff, and runs it even if
 * the user swipes the app away.
 */
@Singleton
class WorkManagerOrderSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : OrderSyncScheduler {

    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OrderSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // Unique work: many orders placed offline still mean one sync job, never parallel duplicates.
        // APPEND_OR_REPLACE: if a sync is already running, run another one after it for the new order.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val UNIQUE_NAME = "order-sync"
    }
}

@HiltWorker
class OrderSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val orderRepository: OrderRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        when (orderRepository.syncPendingOrders()) {
            SyncResult.SUCCESS -> Result.success()
            SyncResult.RETRY -> Result.retry()
        }
}
