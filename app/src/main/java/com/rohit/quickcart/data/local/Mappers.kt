package com.rohit.quickcart.data.local

import com.rohit.quickcart.domain.Bill
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.OrderLine
import com.rohit.quickcart.domain.Product

fun CategoryEntity.toDomain() = Category(id = id, name = name, emoji = emoji)

fun ProductEntity.toDomain() = Product(
    id = id,
    name = name,
    categoryId = categoryId,
    unit = unit,
    pricePaise = pricePaise,
    mrpPaise = mrpPaise,
    emoji = emoji,
    inStock = inStock,
    description = description,
)

fun OrderWithItems.toDomain() = Order(
    localId = order.localId,
    serverId = order.serverId,
    status = order.status,
    bill = Bill(order.itemTotalPaise, order.deliveryFeePaise, order.handlingFeePaise, order.tipPaise),
    itemCount = order.itemCount,
    address = order.address,
    paymentMethod = order.paymentMethod,
    etaMinutes = order.etaMinutes,
    riderProgress = order.riderProgress,
    createdAt = order.createdAt,
    lines = items.map {
        OrderLine(
            productId = it.productId,
            name = it.name,
            emoji = it.emoji,
            unit = it.unit,
            pricePaise = it.pricePaise,
            quantity = it.quantity,
        )
    },
)
