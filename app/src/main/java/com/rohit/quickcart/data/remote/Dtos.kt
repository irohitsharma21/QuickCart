package com.rohit.quickcart.data.remote

import com.rohit.quickcart.data.local.CategoryEntity
import com.rohit.quickcart.data.local.ProductEntity
import kotlinx.serialization.Serializable

/*
 * Network models (DTOs = data transfer objects). They mirror the JSON exactly and are kept
 * separate from Room entities and domain models, so a backend field rename only touches this file.
 */

@Serializable
data class CatalogResponse(
    val categories: List<CategoryDto>,
    val products: List<ProductDto>,
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val emoji: String,
)

@Serializable
data class ProductDto(
    val id: String,
    val name: String,
    val categoryId: String,
    val unit: String,
    val pricePaise: Long,
    val mrpPaise: Long,
    val emoji: String,
    val inStock: Boolean = true,
    /** Optional with a default: an older server that doesn't send it still parses. */
    val description: String = "",
)

@Serializable
data class CreateOrderRequest(
    /** Idempotency key: retrying the same order never creates a second one on the server. */
    val clientOrderId: String,
    val items: List<OrderItemRequest>,
    val address: String,
    val paymentMethod: String,
    val tipPaise: Long = 0,
)

@Serializable
data class OrderItemRequest(
    val productId: String,
    val quantity: Int,
)

@Serializable
data class CreateOrderResponse(
    val orderId: String,
    val status: String,
    val etaMinutes: Int,
)

fun CatalogResponse.toCategoryEntities() =
    categories.mapIndexed { index, dto -> CategoryEntity(dto.id, dto.name, dto.emoji, sortOrder = index) }

fun CatalogResponse.toProductEntities() = products.map { dto ->
    ProductEntity(
        id = dto.id,
        name = dto.name,
        categoryId = dto.categoryId,
        unit = dto.unit,
        pricePaise = dto.pricePaise,
        mrpPaise = dto.mrpPaise,
        emoji = dto.emoji,
        inStock = dto.inStock,
        description = dto.description,
    )
}
