package com.rohit.quickcart.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.rohit.quickcart.domain.OrderStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    abstract fun observeCategories(): Flow<List<CategoryEntity>>

    /**
     * One query handles "all products", "one category", "search" and "search inside a category".
     * Room re-runs it and emits a new list whenever the products table changes.
     */
    @Query(
        """
        SELECT * FROM products
        WHERE (:categoryId IS NULL OR categoryId = :categoryId)
          AND (:query = '' OR name LIKE '%' || :query || '%')
        ORDER BY inStock DESC, name ASC
        """,
    )
    abstract fun observeProducts(categoryId: String?, query: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id IN (:ids)")
    abstract suspend fun getProducts(ids: List<String>): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products")
    abstract suspend fun productCount(): Int

    @Upsert
    abstract suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Upsert
    abstract suspend fun upsertProducts(products: List<ProductEntity>)

    @Query("DELETE FROM products WHERE id NOT IN (:keepIds)")
    abstract suspend fun deleteProductsNotIn(keepIds: List<String>)

    @Query("DELETE FROM categories WHERE id NOT IN (:keepIds)")
    abstract suspend fun deleteCategoriesNotIn(keepIds: List<String>)

    /** Swap in a fresh catalog atomically, so the UI never sees a half-updated list. */
    @Transaction
    open suspend fun replaceCatalog(categories: List<CategoryEntity>, products: List<ProductEntity>) {
        upsertCategories(categories)
        upsertProducts(products)
        deleteProductsNotIn(products.map { it.id })
        deleteCategoriesNotIn(categories.map { it.id })
    }
}

@Dao
abstract class RecentSearchDao {

    @Query("SELECT `query` FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<String>>

    @Upsert
    abstract suspend fun upsert(search: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE `query` NOT IN (SELECT `query` FROM recent_searches ORDER BY searchedAt DESC LIMIT :keep)")
    abstract suspend fun trimTo(keep: Int)

    @Query("DELETE FROM recent_searches")
    abstract suspend fun clear()

    @Transaction
    open suspend fun save(query: String, now: Long, keep: Int) {
        upsert(RecentSearchEntity(query, now))
        trimTo(keep)
    }
}

@Dao
abstract class CartDao {

    @Transaction
    @Query("SELECT * FROM cart_items ORDER BY addedAt ASC")
    abstract fun observeCart(): Flow<List<CartItemWithProduct>>

    @Transaction
    @Query("SELECT * FROM cart_items ORDER BY addedAt ASC")
    abstract suspend fun getCart(): List<CartItemWithProduct>

    @Query("SELECT * FROM cart_items WHERE productId = :productId")
    abstract suspend fun get(productId: String): CartItemEntity?

    @Upsert
    abstract suspend fun upsert(item: CartItemEntity)

    @Query("DELETE FROM cart_items WHERE productId = :productId")
    abstract suspend fun delete(productId: String)

    @Query("DELETE FROM cart_items")
    abstract suspend fun clear()

    /**
     * Read-modify-write in one transaction, so two quick taps on "+" can't both read
     * quantity=1 and both write 2.
     */
    @Transaction
    open suspend fun changeQuantity(productId: String, delta: Int, maxQuantity: Int, now: Long): Int {
        val current = get(productId)
        val newQuantity = ((current?.quantity ?: 0) + delta).coerceIn(0, maxQuantity)
        if (newQuantity == 0) {
            delete(productId)
        } else {
            upsert(CartItemEntity(productId, newQuantity, current?.addedAt ?: now))
        }
        return newQuantity
    }
}

@Dao
abstract class OrderDao {

    @Insert
    abstract suspend fun insertOrder(order: OrderEntity)

    @Insert
    abstract suspend fun insertItems(items: List<OrderItemEntity>)

    @Transaction
    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    abstract fun observeOrders(): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE localId = :localId")
    abstract fun observeOrder(localId: String): Flow<OrderWithItems?>

    @Query("SELECT * FROM orders WHERE localId = :localId")
    abstract suspend fun getOrder(localId: String): OrderEntity?

    @Query("SELECT address FROM orders ORDER BY createdAt DESC LIMIT 1")
    abstract fun observeLastAddress(): Flow<String?>

    @Transaction
    @Query("SELECT * FROM orders WHERE status = :status ORDER BY createdAt ASC")
    abstract suspend fun ordersWithStatus(status: OrderStatus): List<OrderWithItems>

    @Query(
        """
        UPDATE orders SET serverId = :serverId, status = :status, etaMinutes = :etaMinutes, updatedAt = :now
        WHERE localId = :localId
        """,
    )
    abstract suspend fun markSynced(localId: String, serverId: String, status: OrderStatus, etaMinutes: Int?, now: Long)

    @Query(
        """
        UPDATE orders SET status = :status, etaMinutes = :etaMinutes, riderProgress = :riderProgress, updatedAt = :now
        WHERE localId = :localId
        """,
    )
    abstract suspend fun updateTracking(localId: String, status: OrderStatus, etaMinutes: Int?, riderProgress: Float, now: Long)

    /**
     * Applies a live update only if it doesn't move the order *backwards*.
     * Messages can arrive late or out of order (e.g. after a reconnect). "PACKED" arriving
     * after "OUT_FOR_DELIVERY" must not regress the UI.
     * Returns true if the row changed.
     */
    @Transaction
    open suspend fun applyTrackingUpdate(
        localId: String,
        status: OrderStatus,
        etaMinutes: Int?,
        riderProgress: Float,
        now: Long,
    ): Boolean {
        val current = getOrder(localId) ?: return false
        if (current.status.isTerminal) return false
        if (status.ordinal < current.status.ordinal) return false
        if (status == current.status && etaMinutes == current.etaMinutes && riderProgress == current.riderProgress) return false
        updateTracking(localId, status, etaMinutes, riderProgress, now)
        return true
    }
}
