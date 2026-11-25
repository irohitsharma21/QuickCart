package com.rohit.quickcart.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit generates the implementation of this interface at runtime.
 * `suspend` functions are main-safe: Retrofit does the network call on OkHttp's own
 * threads, so callers don't need withContext(Dispatchers.IO).
 */
interface QuickCartApi {

    @GET("catalog")
    suspend fun getCatalog(): CatalogResponse

    @POST("orders")
    suspend fun createOrder(@Body request: CreateOrderRequest): CreateOrderResponse
}
