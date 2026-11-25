package com.rohit.quickcart.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.domain.ReorderPredictor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One horizontal row on the home screen: a category and its first few products. */
data class CategoryRail(val category: Category, val products: List<Product>)

/** A "Running low?" / "Buy again" card. */
data class ReorderItem(val product: Product, val reason: String, val isPrediction: Boolean)

/** Cart numbers shown by the floating cart bar on every browsing screen. */
data class CartSummary(
    val quantities: Map<String, Int> = emptyMap(),
    val itemCount: Int = 0,
    val totalPaise: Long = 0,
    val previewEmojis: List<String> = emptyList(),
)

fun List<CartLine>.toCartSummary() = CartSummary(
    quantities = associate { it.product.id to it.quantity },
    itemCount = sumOf { it.quantity },
    totalPaise = PricingRules.bill(this).itemTotalPaise,
    previewEmojis = takeLast(3).map { it.product.emoji },
)

data class HomeUiState(
    val categories: List<Category> = emptyList(),
    val rails: List<CategoryRail> = emptyList(),
    val reorder: List<ReorderItem> = emptyList(),
    val cart: CartSummary = CartSummary(),
    val deliveryAddress: String? = null,
    val isRefreshing: Boolean = false,
    val isOnline: Boolean = true,
    /** Set when the last refresh failed. */
    val refreshError: String? = null,
) {
    val hasCatalog: Boolean get() = categories.isNotEmpty()
    val showFullScreenLoader: Boolean get() = !hasCatalog && isRefreshing
    val showFullScreenError: Boolean get() = !hasCatalog && !isRefreshing && refreshError != null
}

private data class RefreshState(val isRefreshing: Boolean = false, val error: String? = null)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val cartRepository: CartRepository,
    orderRepository: OrderRepository,
    private val connectivity: ConnectivityObserver,
) : ViewModel() {

    private val refreshState = MutableStateFlow(RefreshState())

    internal var clock: () -> Long = System::currentTimeMillis

    private val catalog = combine(
        catalogRepository.observeCategories(),
        catalogRepository.observeProducts(categoryId = null, query = ""),
        orderRepository.observeOrders(),
    ) { categories, products, orders ->
        val byCategory = products.groupBy { it.categoryId }
        val rails = categories.mapNotNull { category ->
            byCategory[category.id]?.take(RAIL_SIZE)?.let { CategoryRail(category, it) }
        }
        val productsById = products.associateBy { it.id }
        val reorder = ReorderPredictor.suggest(orders, clock()).mapNotNull { suggestion ->
            productsById[suggestion.productId]
                ?.takeIf { it.inStock }
                ?.let { ReorderItem(it, suggestion.reason, suggestion.isPrediction) }
        }
        Triple(categories, rails, reorder)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        catalog,
        cartRepository.observeCart(),
        orderRepository.observeLastAddress(),
        refreshState,
        connectivity.isOnline,
    ) { (categories, rails, reorder), cart, address, refresh, online ->
        HomeUiState(
            categories = categories,
            rails = rails,
            reorder = reorder,
            cart = cart.toCartSummary(),
            deliveryAddress = address,
            isRefreshing = refresh.isRefreshing,
            isOnline = online,
            refreshError = refresh.error,
        )
    }.stateIn(
        scope = viewModelScope,
        // Keep running for 5 s after the UI stops collecting: survives rotation without a re-query,
        // but stops if the user really leaves.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(isRefreshing = true),
    )

    init {
        refresh()
        // When the network comes back after a failed refresh, retry automatically.
        viewModelScope.launch {
            connectivity.isOnline.drop(1).filter { it }.collect {
                if (refreshState.value.error != null) refresh()
            }
        }
    }

    fun onAdd(productId: String) {
        viewModelScope.launch { cartRepository.add(productId) }
    }

    fun onRemove(productId: String) {
        viewModelScope.launch { cartRepository.remove(productId) }
    }

    fun refresh() {
        if (refreshState.value.isRefreshing) return
        viewModelScope.launch {
            refreshState.value = RefreshState(isRefreshing = true)
            val result = catalogRepository.refresh()
            refreshState.value = RefreshState(
                isRefreshing = false,
                error = result.exceptionOrNull()?.let {
                    if (catalogRepository.hasCachedCatalog()) {
                        "Can't reach QuickCart. Showing saved prices."
                    } else {
                        "Couldn't load products. Check your internet connection."
                    }
                },
            )
        }
    }

    private companion object {
        const val RAIL_SIZE = 8
    }
}
