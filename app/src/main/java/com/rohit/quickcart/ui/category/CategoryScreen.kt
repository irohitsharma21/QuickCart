package com.rohit.quickcart.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.ui.components.CartBar
import com.rohit.quickcart.ui.components.ProductCard
import com.rohit.quickcart.ui.components.ProductDetailSheet
import com.rohit.quickcart.ui.home.CartSummary
import com.rohit.quickcart.ui.home.toCartSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryUiState(
    val categories: List<Category> = emptyList(),
    val selectedId: String = "",
    val products: List<Product> = emptyList(),
    val cart: CartSummary = CartSummary(),
) {
    val selected: Category? get() = categories.firstOrNull { it.id == selectedId }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    catalogRepository: CatalogRepository,
    private val cartRepository: CartRepository,
) : ViewModel() {

    // Starts as the category from the route; changes when the user taps the sidebar.
    // Kept in SavedStateHandle so it survives process death.
    private val selectedId = savedStateHandle.getStateFlow(KEY_SELECTED, savedStateHandle.get<String>("categoryId").orEmpty())

    val uiState: StateFlow<CategoryUiState> = combine(
        catalogRepository.observeCategories(),
        selectedId,
        selectedId.flatMapLatest { catalogRepository.observeProducts(it, "") },
        cartRepository.observeCart(),
    ) { categories, selected, products, cart ->
        CategoryUiState(categories, selected, products, cart.toCartSummary())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryUiState())

    fun onSelect(categoryId: String) {
        savedStateHandle[KEY_SELECTED] = categoryId
    }

    fun onAdd(productId: String) {
        viewModelScope.launch { cartRepository.add(productId) }
    }

    fun onRemove(productId: String) {
        viewModelScope.launch { cartRepository.remove(productId) }
    }

    private companion object {
        const val KEY_SELECTED = "selectedCategory"
    }
}

/** Category browsing: categories in a sidebar on the left, products on the right. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenCart: () -> Unit,
    viewModel: CategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetProductId by rememberSaveable { mutableStateOf<String?>(null) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selectedId) { gridState.scrollToItem(0) } // new category starts at the top

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selected?.name ?: "", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") } },
            )
        },
        bottomBar = { CartBar(state.cart.itemCount, state.cart.totalPaise, state.cart.previewEmojis, onOpenCart) },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                Modifier
                    .width(88.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                items(state.categories, key = { it.id }) { category ->
                    SidebarItem(category, selected = category.id == state.selectedId, onClick = { viewModel.onSelect(category.id) })
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).background(MaterialTheme.colorScheme.background),
            ) {
                items(state.products, key = { it.id }) { product ->
                    ProductCard(
                        product = product,
                        quantity = state.cart.quantities[product.id] ?: 0,
                        onAdd = { viewModel.onAdd(product.id) },
                        onRemove = { viewModel.onRemove(product.id) },
                        onClick = { sheetProductId = product.id },
                        emojiSize = 36,
                    )
                }
            }
        }
    }

    state.products.firstOrNull { it.id == sheetProductId }?.let { product ->
        ProductDetailSheet(
            product = product,
            quantity = state.cart.quantities[product.id] ?: 0,
            onAdd = { viewModel.onAdd(product.id) },
            onRemove = { viewModel.onRemove(product.id) },
            onDismiss = { sheetProductId = null },
        )
    }
}

@Composable
private fun SidebarItem(category: Category, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        label = "sidebar",
    )
    // IntrinsicSize.Min lets the selection bar on the right match the item's height.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(background).clickable(onClick = onClick)) {
        Column(
            Modifier.weight(1f).padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(category.emoji, fontSize = 26.sp) }
            Text(
                category.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (selected) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
    }
}
