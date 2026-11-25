package com.rohit.quickcart

import android.app.Application
import app.cash.turbine.test
import com.rohit.quickcart.data.local.QuickCartDatabase
import com.rohit.quickcart.data.remote.CreateOrderRequest
import com.rohit.quickcart.data.remote.OrderItemRequest
import com.rohit.quickcart.data.remote.QuickCartApi
import com.rohit.quickcart.data.remote.fake.FakeCatalog
import com.rohit.quickcart.data.remote.fake.FakeOrderSimulator
import com.rohit.quickcart.data.repository.DefaultOrderRepository
import com.rohit.quickcart.data.repository.LiveConnection
import com.rohit.quickcart.data.repository.OfflineFirstCatalogRepository
import com.rohit.quickcart.data.repository.RoomCartRepository
import com.rohit.quickcart.data.repository.SyncResult
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.PricingRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Data-layer tests: a real Room database (in memory) + real Retrofit/JSON going through
 * the fake backend. Only the network and the clock are simulated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepositoryTest {

    private lateinit var db: QuickCartDatabase
    private lateinit var connectivity: FakeConnectivity
    private lateinit var simulator: FakeOrderSimulator
    private lateinit var api: QuickCartApi
    private lateinit var scheduler: RecordingSyncScheduler
    private lateinit var catalog: OfflineFirstCatalogRepository
    private lateinit var cart: RoomCartRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        connectivity = FakeConnectivity(online = true)
        simulator = FakeOrderSimulator()
        api = fakeBackendApi(connectivity, simulator)
        scheduler = RecordingSyncScheduler()
        catalog = OfflineFirstCatalogRepository(db.catalogDao(), api)
        cart = RoomCartRepository(db.cartDao())
    }

    @After
    fun tearDown() = db.close()

    private fun orders(socket: ScriptedSocket = ScriptedSocket()) =
        DefaultOrderRepository(db, api, socket, scheduler)

    // ---------- catalog ----------

    @Test
    fun refreshFillsRoom_andOfflineRefreshKeepsTheCache() = runTest {
        assertFalse(catalog.hasCachedCatalog())
        assertTrue(catalog.refresh().isSuccess)
        assertEquals(FakeCatalog.response().products.size, catalog.observeProducts(null, "").first().size)

        connectivity.state.value = false
        val result = catalog.refresh()
        assertTrue(result.exceptionOrNull() is IOException)
        assertTrue("cache survives a failed refresh", catalog.hasCachedCatalog())
    }

    @Test
    fun searchAndCategoryFilter_comeFromRoom() = runTest {
        catalog.refresh()
        assertEquals(listOf("Toned Milk"), catalog.observeProducts(null, "milk").first().map { it.name })
        assertEquals(6, catalog.observeProducts("dairy", "").first().size)
        assertEquals(listOf("Fresh Paneer"), catalog.observeProducts("dairy", "  PANEER ").first().map { it.name })
        // Out-of-stock items are sorted to the end.
        assertEquals("Alphonso Mango", catalog.observeProducts("fruits-veg", "").first().last().name)
    }

    // ---------- cart ----------

    @Test
    fun cartQuantityIsCapped_andRemovingTheLastOneDeletesTheRow() = runTest {
        catalog.refresh()
        repeat(PricingRules.MAX_QUANTITY_PER_ITEM + 3) { cart.add("milk") }
        assertEquals(PricingRules.MAX_QUANTITY_PER_ITEM, cart.observeCart().first().single().quantity)

        repeat(PricingRules.MAX_QUANTITY_PER_ITEM) { cart.remove("milk") }
        assertTrue(cart.observeCart().first().isEmpty())
    }

    // ---------- orders ----------

    @Test
    fun placingAnOrderOffline_savesItLocally_clearsTheCart_andSchedulesSync() = runTest {
        catalog.refresh()
        cart.add("milk"); cart.add("milk"); cart.add("bread")
        connectivity.state.value = false

        val localId = orders().placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.CASH_ON_DELIVERY)

        val order = orders().observeOrder(localId).first()!!
        assertEquals(OrderStatus.PENDING_SYNC, order.status)
        assertNull(order.serverId)
        assertEquals(3, order.itemCount)
        assertEquals(28_00 * 2 + 45_00L, order.bill.itemTotalPaise)
        assertTrue(cart.observeCart().first().isEmpty())
        assertEquals(1, scheduler.scheduleCount)
    }

    @Test
    fun placingAnOrderWithAnEmptyCart_fails() = runTest {
        try {
            orders().placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.UPI)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Cart is empty", e.message)
        }
    }

    @Test
    fun syncRetriesWhileOffline_thenMarksTheOrderPlaced() = runTest {
        catalog.refresh()
        cart.add("eggs")
        connectivity.state.value = false
        val repo = orders()
        val localId = repo.placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.UPI)

        assertEquals(SyncResult.RETRY, repo.syncPendingOrders())
        assertEquals(OrderStatus.PENDING_SYNC, repo.observeOrder(localId).first()!!.status)

        connectivity.state.value = true
        assertEquals(SyncResult.SUCCESS, repo.syncPendingOrders())
        val synced = repo.observeOrder(localId).first()!!
        assertEquals(OrderStatus.PLACED, synced.status)
        assertNotNull(synced.serverId)
        assertEquals(1, simulator.orderCount())
    }

    @Test
    fun sameClientOrderId_neverCreatesASecondServerOrder() = runTest {
        val request = CreateOrderRequest("client-uuid-1", listOf(OrderItemRequest("milk", 1)), "addr", "UPI")
        val first = api.createOrder(request)
        val retry = api.createOrder(request) // e.g. the response was lost and WorkManager retried
        assertEquals(first.orderId, retry.orderId)
        assertEquals(1, simulator.orderCount())
    }

    @Test
    fun trackingUpdates_neverMoveTheStatusBackwards() = runTest {
        catalog.refresh()
        cart.add("milk")
        val repo = orders()
        val localId = repo.placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.UPI)
        repo.syncPendingOrders()
        val dao = db.orderDao()

        assertTrue(dao.applyTrackingUpdate(localId, OrderStatus.OUT_FOR_DELIVERY, 4, 0.3f, 1))
        assertFalse("late PACKED is ignored", dao.applyTrackingUpdate(localId, OrderStatus.PACKED, 8, 0f, 2))
        assertEquals(OrderStatus.OUT_FOR_DELIVERY, repo.observeOrder(localId).first()!!.status)

        assertTrue(dao.applyTrackingUpdate(localId, OrderStatus.DELIVERED, 0, 1f, 3))
        assertFalse("nothing changes after delivery", dao.applyTrackingUpdate(localId, OrderStatus.OUT_FOR_DELIVERY, 2, 0.9f, 4))
    }

    @Test
    fun liveTracking_reconnectsAfterADrop_andFinishesWhenDelivered() = runTest {
        catalog.refresh()
        cart.add("milk")
        val socket = ScriptedSocket(
            flow { emit(update("PACKED", eta = 8)); throw IOException("connection dropped") },
            flow { emit(update("OUT_FOR_DELIVERY", eta = 4, progress = 0.5f)); emit(update("DELIVERED", eta = 0, progress = 1f)) },
        )
        val repo = orders(socket)
        val localId = repo.placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.UPI)
        repo.syncPendingOrders()

        val seen = mutableListOf<LiveConnection>()
        repo.trackOrder(localId).test {
            while (true) {
                val item = awaitItem()
                seen += item
                if (item == LiveConnection.Finished) break
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(LiveConnection.Connecting, seen.first())
        assertTrue("reported the reconnect: $seen", seen.any { it is LiveConnection.Reconnecting && it.attempt == 1L })
        assertEquals(2, socket.connections)
        assertEquals(OrderStatus.DELIVERED, repo.observeOrder(localId).first()!!.status)
    }

    @Test
    fun anOrderThatIsNotSyncedYet_waitsInsteadOfConnecting() = runTest {
        catalog.refresh()
        cart.add("milk")
        connectivity.state.value = false
        val socket = ScriptedSocket()
        val repo = orders(socket)
        val localId = repo.placeOrder("Flat 12, Sector 62, Noida", PaymentMethod.UPI)

        repo.trackOrder(localId).test {
            assertEquals(LiveConnection.WaitingForSync, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, socket.connections)
    }
}
