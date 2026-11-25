package com.rohit.quickcart.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.domain.PaymentMethod

/*
 * Room tables. Room is the app's single source of truth: every screen reads from these
 * tables (as Flows), and the network only ever *writes* into them.
 */

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val sortOrder: Int,
)

@Entity(tableName = "products", indices = [Index("categoryId")])
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val categoryId: String,
    val unit: String,
    val pricePaise: Long,
    val mrpPaise: Long,
    val emoji: String,
    val inStock: Boolean,
    @ColumnInfo(defaultValue = "") val description: String = "", // added in DB version 2
)

/** Recent searches for the search screen (added in DB version 2). */
@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

/** The cart lives only on the device, so it survives app restarts and having no network. */
@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey val productId: String,
    val quantity: Int,
    val addedAt: Long,
)

/** A cart row joined with its product (Room fills in [product] via @Relation). */
data class CartItemWithProduct(
    @Embedded val item: CartItemEntity,
    @Relation(parentColumn = "productId", entityColumn = "id")
    val product: ProductEntity?,
)

@Entity(tableName = "orders", indices = [Index("status")])
data class OrderEntity(
    /** UUID made on the phone. Also sent to the server as the idempotency key. */
    @PrimaryKey val localId: String,
    /** Set once the server accepts the order. */
    val serverId: String?,
    val status: OrderStatus,
    val itemTotalPaise: Long,
    val deliveryFeePaise: Long,
    val handlingFeePaise: Long,
    @ColumnInfo(defaultValue = "0") val tipPaise: Long = 0, // added in DB version 2
    val itemCount: Int,
    val address: String,
    val paymentMethod: PaymentMethod,
    val etaMinutes: Int?,
    val riderProgress: Float,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "order_items",
    primaryKeys = ["orderLocalId", "productId"],
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["localId"],
            childColumns = ["orderLocalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("orderLocalId")],
)
data class OrderItemEntity(
    val orderLocalId: String,
    val productId: String,
    /** Name and price are copied at order time, so later catalog price changes don't rewrite history. */
    val name: String,
    val emoji: String,
    val unit: String,
    val pricePaise: Long,
    val quantity: Int,
)

data class OrderWithItems(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "localId", entityColumn = "orderLocalId")
    val items: List<OrderItemEntity>,
)
