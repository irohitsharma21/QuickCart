package com.rohit.quickcart.data.repository

import com.rohit.quickcart.data.local.CatalogDao
import com.rohit.quickcart.data.local.toDomain
import com.rohit.quickcart.data.remote.QuickCartApi
import com.rohit.quickcart.data.remote.toCategoryEntities
import com.rohit.quickcart.data.remote.toProductEntities
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.Product
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface CatalogRepository {
    fun observeCategories(): Flow<List<Category>>
    fun observeProducts(categoryId: String?, query: String): Flow<List<Product>>

    /** Fetch from the network and write into Room. The UI updates through the Flows above. */
    suspend fun refresh(): Result<Unit>
    suspend fun hasCachedCatalog(): Boolean
}

/**
 * Offline-first: reads come only from Room. refresh() is the only place that touches
 * the network, and all it does is write into Room. If the network fails, the user keeps
 * seeing the last saved catalog.
 */
@Singleton
class OfflineFirstCatalogRepository @Inject constructor(
    private val dao: CatalogDao,
    private val api: QuickCartApi,
) : CatalogRepository {

    override fun observeCategories(): Flow<List<Category>> =
        dao.observeCategories().map { list -> list.map { it.toDomain() } }

    override fun observeProducts(categoryId: String?, query: String): Flow<List<Product>> =
        dao.observeProducts(categoryId, query.trim()).map { list -> list.map { it.toDomain() } }

    override suspend fun refresh(): Result<Unit> =
        try {
            val catalog = api.getCatalog()
            dao.replaceCatalog(catalog.toCategoryEntities(), catalog.toProductEntities())
            Result.success(Unit)
        } catch (e: IOException) {          // no network / timeout
            Result.failure(e)
        } catch (e: HttpException) {        // 4xx / 5xx
            Result.failure(e)
        } catch (e: SerializationException) { // server sent unexpected JSON
            Result.failure(e)
        }
    // CancellationException is deliberately NOT caught: swallowing it would break structured concurrency.

    override suspend fun hasCachedCatalog(): Boolean = dao.productCount() > 0
}
