package com.rohit.quickcart.data.repository

import com.rohit.quickcart.data.local.CartDao
import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PricingRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface CartRepository {
    fun observeCart(): Flow<List<CartLine>>
    suspend fun add(productId: String, count: Int = 1)
    suspend fun remove(productId: String)
    suspend fun clear()
}

@Singleton
class RoomCartRepository @Inject constructor(
    private val dao: CartDao,
) : CartRepository {

    override fun observeCart(): Flow<List<CartLine>> =
        dao.observeCart().map { rows ->
            // If a product disappeared from the catalog, drop it from the visible cart.
            rows.mapNotNull { row -> row.product?.let { CartLine(it.toDomain(), row.item.quantity) } }
        }

    override suspend fun add(productId: String, count: Int) {
        dao.changeQuantity(productId, count, PricingRules.MAX_QUANTITY_PER_ITEM, System.currentTimeMillis())
    }

    override suspend fun remove(productId: String) {
        dao.changeQuantity(productId, -1, PricingRules.MAX_QUANTITY_PER_ITEM, System.currentTimeMillis())
    }

    override suspend fun clear() = dao.clear()
}
