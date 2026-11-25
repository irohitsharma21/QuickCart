package com.rohit.quickcart.di

import android.content.Context
import androidx.room.Room
import com.rohit.quickcart.BuildConfig
import com.rohit.quickcart.data.AndroidConnectivityObserver
import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.local.CartDao
import com.rohit.quickcart.data.local.CatalogDao
import com.rohit.quickcart.data.local.ALL_MIGRATIONS
import com.rohit.quickcart.data.local.QuickCartDatabase
import com.rohit.quickcart.data.local.RecentSearchDao
import com.rohit.quickcart.data.realtime.FakeOrderTrackingSocket
import com.rohit.quickcart.data.realtime.OkHttpOrderTrackingSocket
import com.rohit.quickcart.data.realtime.OrderTrackingSocket
import com.rohit.quickcart.data.remote.QuickCartApi
import com.rohit.quickcart.data.remote.fake.FakeBackendInterceptor
import com.rohit.quickcart.data.remote.fake.FakeOrderSimulator
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.repository.DefaultOrderRepository
import com.rohit.quickcart.data.repository.OfflineFirstCatalogRepository
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.data.repository.RoomCartRepository
import com.rohit.quickcart.data.repository.RoomSearchRepository
import com.rohit.quickcart.data.repository.SearchRepository
import com.rohit.quickcart.data.sync.OrderSyncScheduler
import com.rohit.quickcart.data.voice.AndroidSpeechIo
import com.rohit.quickcart.data.voice.SpeechIo
import com.rohit.quickcart.data.sync.WorkManagerOrderSyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** A scope that lives as long as the app process (unlike viewModelScope). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): QuickCartDatabase =
        Room.databaseBuilder(context, QuickCartDatabase::class.java, "quickcart.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideCatalogDao(db: QuickCartDatabase): CatalogDao = db.catalogDao()

    @Provides
    fun provideCartDao(db: QuickCartDatabase): CartDao = db.cartDao()

    @Provides
    fun provideRecentSearchDao(db: QuickCartDatabase): RecentSearchDao = db.recentSearchDao()
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private const val FAKE_BASE_URL = "https://api.quickcart.fake/"

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true // a new field added on the server must not crash old app versions
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        json: Json,
        connectivity: ConnectivityObserver,
        simulator: FakeOrderSimulator,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS) // WebSocket heartbeat: detects dead connections
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }
        if (BuildConfig.USE_FAKE_BACKEND) {
            builder.addInterceptor(FakeBackendInterceptor(connectivity, simulator, json))
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (BuildConfig.USE_FAKE_BACKEND) FAKE_BASE_URL else BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideApi(retrofit: Retrofit): QuickCartApi = retrofit.create(QuickCartApi::class.java)

    @Provides
    @Singleton
    fun provideTrackingSocket(
        client: OkHttpClient,
        json: Json,
        simulator: FakeOrderSimulator,
        connectivity: ConnectivityObserver,
    ): OrderTrackingSocket =
        if (BuildConfig.USE_FAKE_BACKEND) {
            FakeOrderTrackingSocket(simulator, connectivity)
        } else {
            OkHttpOrderTrackingSocket(client, BuildConfig.WS_BASE_URL, json)
        }
}

/** @Binds tells Hilt "when someone asks for the interface, give them this implementation". */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    abstract fun bindConnectivity(impl: AndroidConnectivityObserver): ConnectivityObserver

    @Binds
    abstract fun bindCatalogRepository(impl: OfflineFirstCatalogRepository): CatalogRepository

    @Binds
    abstract fun bindCartRepository(impl: RoomCartRepository): CartRepository

    @Binds
    abstract fun bindSearchRepository(impl: RoomSearchRepository): SearchRepository

    @Binds
    abstract fun bindOrderRepository(impl: DefaultOrderRepository): OrderRepository

    /** Not @Singleton: each voice-agent screen gets its own mic/TTS instance and releases it. */
    @Binds
    abstract fun bindSpeechIo(impl: AndroidSpeechIo): SpeechIo

    @Binds
    abstract fun bindSyncScheduler(impl: WorkManagerOrderSyncScheduler): OrderSyncScheduler
}
