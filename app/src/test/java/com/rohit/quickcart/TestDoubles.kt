package com.rohit.quickcart

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.local.QuickCartDatabase
import com.rohit.quickcart.data.realtime.OrderTrackingSocket
import com.rohit.quickcart.data.realtime.TrackingUpdate
import com.rohit.quickcart.data.remote.QuickCartApi
import com.rohit.quickcart.data.remote.fake.FakeBackendInterceptor
import com.rohit.quickcart.data.remote.fake.FakeOrderSimulator
import com.rohit.quickcart.data.sync.OrderSyncScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class FakeConnectivity(online: Boolean = true) : ConnectivityObserver {
    val state = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = state
    override fun isOnlineNow(): Boolean = state.value
}

class RecordingSyncScheduler : OrderSyncScheduler {
    var scheduleCount = 0
    override fun schedule() {
        scheduleCount++
    }
}

/** Each connection attempt plays the next script, so tests can simulate "drop, then reconnect". */
class ScriptedSocket(vararg scripts: Flow<TrackingUpdate>) : OrderTrackingSocket {
    private val queue = ArrayDeque(scripts.toList())
    var connections = 0
        private set

    override fun track(serverOrderId: String): Flow<TrackingUpdate> = flow {
        connections++
        emitAll(queue.removeFirstOrNull() ?: emptyFlow())
    }
}

val testJson = Json { ignoreUnknownKeys = true }

/** Real Retrofit + real JSON parsing, answered by the in-app fake backend. */
fun fakeBackendApi(connectivity: ConnectivityObserver, simulator: FakeOrderSimulator): QuickCartApi =
    Retrofit.Builder()
        .baseUrl("https://api.quickcart.fake/")
        .client(OkHttpClient.Builder().addInterceptor(FakeBackendInterceptor(connectivity, simulator, testJson, latencyMs = 0)).build())
        .addConverterFactory(testJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(QuickCartApi::class.java)

fun inMemoryDatabase(): QuickCartDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), QuickCartDatabase::class.java)
        .allowMainThreadQueries()
        .build()

fun update(status: String, eta: Int = 5, progress: Float = 0f) = TrackingUpdate("QC1", status, eta, progress, status)
