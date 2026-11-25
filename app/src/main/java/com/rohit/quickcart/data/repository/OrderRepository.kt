package com.rohit.quickcart.data.repository

import androidx.room.withTransaction
import com.rohit.quickcart.data.local.OrderDao
import com.rohit.quickcart.data.local.OrderEntity
import com.rohit.quickcart.data.local.OrderItemEntity
import com.rohit.quickcart.data.local.QuickCartDatabase
import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.data.realtime.Backoff
import com.rohit.quickcart.data.realtime.OrderTrackingSocket
import com.rohit.quickcart.data.remote.CreateOrderRequest
import com.rohit.quickcart.data.remote.OrderItemRequest
import com.rohit.quickcart.data.remote.QuickCartApi
import com.rohit.quickcart.data.sync.OrderSyncScheduler
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.PricingRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** State of the live-tracking connection, shown as a small chip on the tracking screen. */
sealed interface LiveConnection {
    data object Connecting : LiveConnection
    data object Live : LiveConnection
    data class Reconnecting(val attempt: Long, val retryInMs: Long) : LiveConnection
    /** The order hasn't reached the server yet (placed offline), so there's nothing to track. */
    data object WaitingForSync : LiveConnection
    /** Delivered or cancelled. The socket is closed. */
    data object Finished : LiveConnection
}

enum class SyncResult { SUCCESS, RETRY }

interface OrderRepository {
    fun observeOrders(): Flow<List<Order>>
    fun observeOrder(localId: String): Flow<Order?>

    /** Address of the most recent order: shown in the home header. */
    fun observeLastAddress(): Flow<String?>

    /** Turns the cart into a local order and returns its localId. Works offline. */
    suspend fun placeOrder(address: String, paymentMethod: PaymentMethod, tipPaise: Long = 0): String

    /** Sends every PENDING_SYNC order to the server. Called by [com.rohit.quickcart.data.sync.OrderSyncWorker]. */
    suspend fun syncPendingOrders(): SyncResult

    /**
     * Opens the live-tracking socket for an order and writes every update into Room.
     * The UI reads the order from Room. This Flow only reports connection state.
     */
    fun trackOrder(localId: String): Flow<LiveConnection>

    /** Debug builds only: adds a few past orders so the "Running low?" prediction can be demoed. */
    suspend fun addDemoHistory()
}

@Singleton
class DefaultOrderRepository @Inject constructor(
    private val db: QuickCartDatabase,
    private val api: QuickCartApi,
    private val socket: OrderTrackingSocket,
    private val syncScheduler: OrderSyncScheduler,
) : OrderRepository {

    private val orderDao: OrderDao = db.orderDao()
    private val cartDao = db.cartDao()

    internal var idGenerator: () -> String = { UUID.randomUUID().toString() }
    internal var clock: () -> Long = System::currentTimeMillis

    override fun observeOrders(): Flow<List<Order>> =
        orderDao.observeOrders().map { list -> list.map { it.toDomain() } }

    override fun observeOrder(localId: String): Flow<Order?> =
        orderDao.observeOrder(localId).map { it?.toDomain() }

    override fun observeLastAddress(): Flow<String?> = orderDao.observeLastAddress()

    override suspend fun placeOrder(address: String, paymentMethod: PaymentMethod, tipPaise: Long): String {
        val localId = idGenerator()
        val now = clock()
        // One transaction: read cart -> insert order + items -> clear cart.
        // If the app dies halfway, either everything happened or nothing did.
        db.withTransaction {
            val lines = cartDao.getCart().mapNotNull { row ->
                row.product?.takeIf { it.inStock }?.let { CartLine(it.toDomain(), row.item.quantity) }
            }
            check(lines.isNotEmpty()) { "Cart is empty" }
            val bill = PricingRules.bill(lines, tipPaise)
            orderDao.insertOrder(
                OrderEntity(
                    localId = localId,
                    serverId = null,
                    status = OrderStatus.PENDING_SYNC,
                    itemTotalPaise = bill.itemTotalPaise,
                    deliveryFeePaise = bill.deliveryFeePaise,
                    handlingFeePaise = bill.handlingFeePaise,
                    tipPaise = bill.tipPaise,
                    itemCount = lines.sumOf { it.quantity },
                    address = address.trim(),
                    paymentMethod = paymentMethod,
                    etaMinutes = null,
                    riderProgress = 0f,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            orderDao.insertItems(
                lines.map {
                    OrderItemEntity(
                        orderLocalId = localId,
                        productId = it.product.id,
                        name = it.product.name,
                        emoji = it.product.emoji,
                        unit = it.product.unit,
                        pricePaise = it.product.pricePaise,
                        quantity = it.quantity,
                    )
                },
            )
            cartDao.clear()
        }
        // WorkManager sends it as soon as there's network: right away if online,
        // or later, even if the app has been closed.
        syncScheduler.schedule()
        return localId
    }

    override suspend fun syncPendingOrders(): SyncResult {
        var retry = false
        for (pending in orderDao.ordersWithStatus(OrderStatus.PENDING_SYNC)) {
            val order = pending.order
            val request = CreateOrderRequest(
                clientOrderId = order.localId, // idempotency key
                items = pending.items.map { OrderItemRequest(it.productId, it.quantity) },
                address = order.address,
                paymentMethod = order.paymentMethod.name,
                tipPaise = order.tipPaise,
            )
            try {
                val response = api.createOrder(request)
                val status = OrderStatus.entries.firstOrNull { it.name == response.status } ?: OrderStatus.PLACED
                orderDao.markSynced(order.localId, response.orderId, status, response.etaMinutes, clock())
            } catch (e: IOException) {
                retry = true // offline or timeout: WorkManager will try again with backoff
            } catch (e: HttpException) {
                if (e.code() >= 500) {
                    retry = true // server problem: try again later
                } else {
                    // 4xx: the server rejected this order (e.g. item no longer sold). Retrying won't help.
                    orderDao.updateTracking(order.localId, OrderStatus.CANCELLED, null, 0f, clock())
                }
            }
        }
        return if (retry) SyncResult.RETRY else SyncResult.SUCCESS
    }

    override suspend fun addDemoHistory() {
        val day = 24 * 60 * 60 * 1000L
        val now = clock()
        // Milk + bread every 2 days, eggs every 4 days, atta once.
        val history = listOf(
            8 to listOf("milk" to 2, "bread" to 1, "eggs" to 1, "atta" to 1),
            6 to listOf("milk" to 2, "bread" to 1),
            4 to listOf("milk" to 2, "bread" to 1, "eggs" to 1),
            2 to listOf("milk" to 2, "bread" to 1, "banana" to 1),
        )
        val products = db.catalogDao().getProducts(history.flatMap { (_, items) -> items.map { it.first } }.distinct())
            .associateBy { it.id }
        if (products.isEmpty()) return
        db.withTransaction {
            history.forEachIndexed { index, (daysAgo, items) ->
                val localId = "demo-$index-${now / day}"
                if (orderDao.getOrder(localId) != null) return@forEachIndexed // tapped twice today
                val lines = items.mapNotNull { (id, qty) -> products[id]?.let { it to qty } }
                val itemTotal = lines.sumOf { (p, q) -> p.pricePaise * q }
                val placedAt = now - daysAgo * day
                orderDao.insertOrder(
                    OrderEntity(
                        localId = localId, serverId = "QC${100_000 + index}", status = OrderStatus.DELIVERED,
                        itemTotalPaise = itemTotal, deliveryFeePaise = 0, handlingFeePaise = PricingRules.HANDLING_FEE_PAISE,
                        itemCount = lines.sumOf { it.second }, address = "Flat 12, Sector 62, Noida",
                        paymentMethod = PaymentMethod.UPI, etaMinutes = 0, riderProgress = 1f, createdAt = placedAt, updatedAt = placedAt,
                    ),
                )
                orderDao.insertItems(
                    lines.map { (p, q) -> OrderItemEntity(localId, p.id, p.name, p.emoji, p.unit, p.pricePaise, q) },
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun trackOrder(localId: String): Flow<LiveConnection> =
        orderDao.observeOrder(localId)
            .map { row -> TrackingKey(row?.order?.serverId, row?.order?.status?.isTerminal ?: true) }
            .distinctUntilChanged()
            // flatMapLatest: when the key changes (order synced, or delivered), the old socket is cancelled.
            .flatMapLatest { key ->
                when {
                    key.isTerminal -> flowOf(LiveConnection.Finished)
                    key.serverId == null -> flowOf(LiveConnection.WaitingForSync)
                    else -> liveUpdates(localId, key.serverId)
                }
            }

    private data class TrackingKey(val serverId: String?, val isTerminal: Boolean)

    private fun liveUpdates(localId: String, serverId: String): Flow<LiveConnection> =
        socket.track(serverId)
            .map { update ->
                val status = update.orderStatus
                if (status != null) {
                    orderDao.applyTrackingUpdate(localId, status, update.etaMinutes, update.riderProgress, clock())
                }
                LiveConnection.Live as LiveConnection
            }
            .onStart { emit(LiveConnection.Connecting) }
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                val wait = Backoff.delayMillis(attempt)
                emit(LiveConnection.Reconnecting(attempt + 1, wait))
                delay(wait)
                true
            }
}
