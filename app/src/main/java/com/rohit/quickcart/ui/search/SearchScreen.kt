package com.rohit.quickcart.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.data.repository.SearchRepository
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.ui.components.CartBar
import com.rohit.quickcart.ui.components.EmptyState
import com.rohit.quickcart.ui.components.ProductCard
import com.rohit.quickcart.ui.components.ProductDetailSheet
import com.rohit.quickcart.ui.home.CartSummary
import com.rohit.quickcart.ui.home.toCartSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<Product> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val trending: List<String> = SearchViewModel.TRENDING,
    val cart: CartSummary = CartSummary(),
) {
    val showSuggestions: Boolean get() = query.isBlank()
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val cartRepository: CartRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    /**
     * Search pipeline:
     * - debounce(250): wait until the user stops typing for 250 ms, instead of querying on every key.
     *   An empty query is NOT delayed (clearing the box is instant).
     * - distinctUntilChanged: "milk" -> "milk " -> "milk" shouldn't query three times
     * - flatMapLatest: a new query cancels the previous one, so old results can't overwrite new ones
     */
    private val results = query
        .debounce { if (it.isBlank()) 0L else 250L }
        .map { it.trim() }
        .distinctUntilChanged()
        .flatMapLatest { q -> if (q.isEmpty()) flowOf(emptyList()) else catalogRepository.observeProducts(null, q) }

    val uiState: StateFlow<SearchUiState> = combine(
        query,
        results,
        searchRepository.observeRecentSearches(),
        cartRepository.observeCart(),
    ) { q, results, recent, cart ->
        SearchUiState(query = q, results = results, recentSearches = recent, cart = cart.toCartSummary())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** Keyboard "search" pressed, or a suggestion chip tapped. */
    fun onSubmit(text: String = query.value) {
        query.value = text
        viewModelScope.launch { searchRepository.saveSearch(text) }
    }

    fun onAdd(productId: String) {
        viewModelScope.launch {
            cartRepository.add(productId)
            // Adding from results means the search worked, so remember it.
            searchRepository.saveSearch(query.value)
        }
    }

    fun onRemove(productId: String) {
        viewModelScope.launch { cartRepository.remove(productId) }
    }

    fun onClearRecent() {
        viewModelScope.launch { searchRepository.clearRecentSearches() }
    }

    companion object {
        val TRENDING = listOf("milk", "bread", "eggs", "atta", "chips", "banana", "paneer", "cold coffee")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenCart: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetProductId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() } // keyboard opens straight away

    Scaffold(
        topBar = {
            Row(Modifier.padding(top = 36.dp, start = 4.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text("Search for atta, dal, coke and more") },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.onSubmit() }),
                )
            }
        },
        bottomBar = { CartBar(state.cart.itemCount, state.cart.totalPaise, state.cart.previewEmojis, onOpenCart) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.showSuggestions -> Column(Modifier.padding(16.dp)) {
                    if (state.recentSearches.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::onClearRecent) { Text("Clear") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.recentSearches.forEach { AssistChip(onClick = { viewModel.onSubmit(it) }, label = { Text("🕘 $it") }) }
                        }
                    }
                    Text("Trending near you", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.trending.forEach { AssistChip(onClick = { viewModel.onSubmit(it) }, label = { Text("📈 $it") }) }
                    }
                }

                state.results.isEmpty() -> EmptyState("🔍", "No results for \"${state.query.trim()}\"", "Try another word, or check the spelling.")

                else -> {
                    Text(
                        "${state.results.size} results for \"${state.query.trim()}\"",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(state.results, key = { it.id }) { product ->
                            ProductCard(
                                product = product,
                                quantity = state.cart.quantities[product.id] ?: 0,
                                onAdd = { viewModel.onAdd(product.id) },
                                onRemove = { viewModel.onRemove(product.id) },
                                onClick = { sheetProductId = product.id },
                            )
                        }
                    }
                }
            }
        }
    }

    state.results.firstOrNull { it.id == sheetProductId }?.let { product ->
        ProductDetailSheet(
            product = product,
            quantity = state.cart.quantities[product.id] ?: 0,
            onAdd = { viewModel.onAdd(product.id) },
            onRemove = { viewModel.onRemove(product.id) },
            onDismiss = { sheetProductId = null },
        )
    }
}
