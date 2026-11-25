package com.rohit.quickcart

import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.repository.LiveConnection
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.data.repository.SearchRepository
import com.rohit.quickcart.data.repository.SyncResult
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.ui.home.HomeViewModel
import com.rohit.quickcart.ui.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** ViewModel tests with plain in-memory fakes: no Android, no Room, runs in milliseconds. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val products = listOf(
        Product("milk", "Toned Milk", "dairy", "500 ml", 28_00, 28_00, "🥛", true),
        Product("bread", "Whole Wheat Bread", "dairy", "400 g", 45_00, 50_00, "🍞", true),
        Product("chips", "Classic Salted Chips", "snacks", "52 g", 20_00, 20_00, "🥔", true),
    )

    private inner class FakeCatalogRepository(var refreshResult: Result<Unit>, var cached: Boolean) : CatalogRepository {
        val queries = mutableListOf<String>()
        override fun observeCategories(): Flow<List<Category>> =
            flowOf(if (cached) listOf(Category("dairy", "Dairy", "🥛"), Category("snacks", "Snacks", "🍿")) else emptyList())

        override fun observeProducts(categoryId: String?, query: String): Flow<List<Product>> {
            if (query.isNotEmpty()) queries += query
            return flowOf(
                if (!cached) emptyList()
                else products.filter { (categoryId == null || it.categoryId == categoryId) && it.name.contains(query, ignoreCase = true) },
            )
        }

        override suspend fun refresh() = refreshResult
        override suspend fun hasCachedCatalog() = cached
    }

    private inner class FakeCartRepository : CartRepository {
        val quantities = MutableStateFlow<Map<String, Int>>(emptyMap())
        override fun observeCart() = quantities.map { map -> map.map { (id, q) -> CartLine(products.first { it.id == id }, q) } }
        override suspend fun add(productId: String, count: Int) {
            quantities.value = quantities.value + (productId to (quantities.value[productId] ?: 0) + count)
        }
        override suspend fun remove(productId: String) {
            val q = (quantities.value[productId] ?: 0) - 1
            quantities.value = if (q <= 0) quantities.value - productId else quantities.value + (productId to q)
        }
        override suspend fun clear() {
            quantities.value = emptyMap()
        }
    }

    private class FakeOrderRepository : OrderRepository {
        override fun observeOrders(): Flow<List<Order>> = flowOf(emptyList())
        override fun observeOrder(localId: String): Flow<Order?> = flowOf(null)
        override fun observeLastAddress(): Flow<String?> = flowOf("Flat 12, Sector 62")
        override suspend fun placeOrder(address: String, paymentMethod: PaymentMethod, tipPaise: Long) = "id"
        override suspend fun syncPendingOrders() = SyncResult.SUCCESS
        override fun trackOrder(localId: String): Flow<LiveConnection> = emptyFlow()
        override suspend fun addDemoHistory() = Unit
    }

    private class FakeSearchRepository : SearchRepository {
        val saved = MutableStateFlow<List<String>>(emptyList())
        override fun observeRecentSearches(): Flow<List<String>> = saved
        override suspend fun saveSearch(query: String) {
            saved.value = listOf(query.trim().lowercase()) + saved.value
        }
        override suspend fun clearRecentSearches() {
            saved.value = emptyList()
        }
    }

    private fun home(catalog: FakeCatalogRepository, connectivity: FakeConnectivity = FakeConnectivity(), cart: FakeCartRepository = FakeCartRepository()) =
        HomeViewModel(catalog, cart, FakeOrderRepository(), connectivity)

    // ---------- Home ----------

    @Test
    fun homeBuildsOneRailPerCategory_andShowsLastAddress() = runTest(dispatcher) {
        val vm = home(FakeCatalogRepository(Result.success(Unit), cached = true))
        backgroundScope.launch { vm.uiState.collect {} }
        val state = vm.uiState.value
        assertEquals(listOf("dairy", "snacks"), state.rails.map { it.category.id })
        assertEquals(listOf("milk", "bread"), state.rails.first().products.map { it.id })
        assertEquals("Flat 12, Sector 62", state.deliveryAddress)
    }

    @Test
    fun addingToCart_updatesTheCartBar() = runTest(dispatcher) {
        val vm = home(FakeCatalogRepository(Result.success(Unit), cached = true))
        backgroundScope.launch { vm.uiState.collect {} }

        vm.onAdd("milk"); vm.onAdd("milk"); vm.onAdd("bread")
        val cart = vm.uiState.value.cart
        assertEquals(3, cart.itemCount)
        assertEquals(2, cart.quantities["milk"])
        assertEquals(28_00 * 2 + 45_00L, cart.totalPaise)
        assertEquals(listOf("🥛", "🍞"), cart.previewEmojis)
    }

    @Test
    fun failedRefreshWithCache_showsSavedCatalogBanner() = runTest(dispatcher) {
        val vm = home(FakeCatalogRepository(Result.failure(IOException()), cached = true), FakeConnectivity(false))
        backgroundScope.launch { vm.uiState.collect {} }
        val state = vm.uiState.value
        assertEquals("Can't reach QuickCart. Showing saved prices.", state.refreshError)
        assertFalse(state.showFullScreenError)
    }

    @Test
    fun failedRefreshWithoutCache_showsFullScreenError_andRetriesWhenBackOnline() = runTest(dispatcher) {
        val catalog = FakeCatalogRepository(Result.failure(IOException()), cached = false)
        val connectivity = FakeConnectivity(false)
        val vm = home(catalog, connectivity)
        backgroundScope.launch { vm.uiState.collect {} }
        assertTrue(vm.uiState.value.showFullScreenError)

        catalog.refreshResult = Result.success(Unit)
        connectivity.state.value = true
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.refreshError)
    }

    // ---------- Search ----------

    @Test
    fun searchIsDebounced_soTypingQueriesRoomOnlyOnce() = runTest(dispatcher) {
        val catalog = FakeCatalogRepository(Result.success(Unit), cached = true)
        val vm = SearchViewModel(catalog, FakeCartRepository(), FakeSearchRepository())
        backgroundScope.launch { vm.uiState.collect {} }

        vm.onQueryChange("m")
        vm.onQueryChange("mi")
        vm.onQueryChange("milk")
        assertEquals("text field updates immediately", "milk", vm.uiState.value.query)
        advanceTimeBy(200)
        assertTrue("no query before 250 ms of quiet", catalog.queries.isEmpty())

        advanceTimeBy(100)
        assertEquals(listOf("milk"), catalog.queries)
        assertEquals(listOf("Toned Milk"), vm.uiState.value.results.map { it.name })
    }

    @Test
    fun submittingOrAddingFromResults_savesARecentSearch() = runTest(dispatcher) {
        val searches = FakeSearchRepository()
        val vm = SearchViewModel(FakeCatalogRepository(Result.success(Unit), cached = true), FakeCartRepository(), searches)
        backgroundScope.launch { vm.uiState.collect {} }

        vm.onSubmit("Bread")
        advanceUntilIdle()
        assertEquals(listOf("bread"), vm.uiState.value.recentSearches)
        assertTrue(vm.uiState.value.results.isNotEmpty())
    }
}
