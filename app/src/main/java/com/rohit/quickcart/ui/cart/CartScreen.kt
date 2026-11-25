package com.rohit.quickcart.ui.cart

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.domain.Bill
import com.rohit.quickcart.domain.CartLine
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.ui.components.BillDetails
import com.rohit.quickcart.ui.components.EmptyState
import com.rohit.quickcart.ui.components.PrimaryBottomButton
import com.rohit.quickcart.ui.components.ProductCard
import com.rohit.quickcart.ui.components.ProductDetailSheet
import com.rohit.quickcart.ui.components.QuantityStepper
import com.rohit.quickcart.ui.components.SectionHeader
import com.rohit.quickcart.util.formatRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CartUiState(
    val isLoading: Boolean = true,
    val lines: List<CartLine> = emptyList(),
    val bill: Bill = Bill(0, 0, 0),
    val savingsPaise: Long = 0,
    val amountForFreeDeliveryPaise: Long = 0,
    val freeDeliveryProgress: Float = 0f,
    /** "You might also like": cheap, in-stock items not already in the cart. */
    val suggestions: List<Product> = emptyList(),
)

@HiltViewModel
class CartViewModel @Inject constructor(
    private val cartRepository: CartRepository,
    catalogRepository: CatalogRepository,
) : ViewModel() {

    val uiState: StateFlow<CartUiState> = combine(
        cartRepository.observeCart(),
        catalogRepository.observeProducts(null, ""),
    ) { lines, products ->
        val bill = PricingRules.bill(lines)
        val inCart = lines.map { it.product.id }.toSet()
        val inCartCategories = lines.map { it.product.categoryId }.toSet()
        CartUiState(
            isLoading = false,
            lines = lines,
            bill = bill,
            savingsPaise = PricingRules.savings(lines),
            amountForFreeDeliveryPaise = PricingRules.amountForFreeDelivery(bill.itemTotalPaise),
            freeDeliveryProgress = PricingRules.freeDeliveryProgress(bill.itemTotalPaise),
            // Items from the same categories first, then the cheapest: small add-ons that push the
            // cart over the free-delivery line.
            suggestions = products
                .filter { it.inStock && it.id !in inCart }
                .sortedWith(compareBy<Product>({ it.categoryId !in inCartCategories }, { it.pricePaise }))
                .take(8),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CartUiState())

    fun onAdd(productId: String) {
        viewModelScope.launch { cartRepository.add(productId) }
    }

    fun onRemove(productId: String) {
        viewModelScope.launch { cartRepository.remove(productId) }
    }

    fun onClear() {
        viewModelScope.launch { cartRepository.clear() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartScreen(
    onBack: () -> Unit,
    onCheckout: () -> Unit,
    viewModel: CartViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sheetProductId by rememberSaveable { mutableStateOf<String?>(null) }
    val quantities = state.lines.associate { it.product.id to it.quantity }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My cart", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { if (state.lines.isNotEmpty()) TextButton(onClick = viewModel::onClear) { Text("Clear") } },
            )
        },
        bottomBar = {
            if (state.lines.isNotEmpty()) PrimaryBottomButton("Proceed to pay · ${state.bill.totalPaise.formatRupees()}", onCheckout)
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (!state.isLoading && state.lines.isEmpty()) {
                EmptyState("🛒", "Your cart is empty", "Add items from the home screen.") {
                    Button(onClick = onBack) { Text("Start shopping") }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The savings banner lives INSIDE this always-present first item. As a separate item that
                    // appears after the first frame, LazyColumn would keep the old first item anchored and
                    // push the banner off-screen above it.
                    item(key = "shipment") {
                        if (state.savingsPaise > 0) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            ) {
                                Text(
                                    "🎉  You're saving ${state.savingsPaise.formatRupees()} on this order",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("⏱  Delivery in 10 minutes", fontWeight = FontWeight.Bold)
                                Text(
                                    "Shipment of ${state.lines.sumOf { it.quantity }} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(10.dp))
                                FreeDeliveryProgress(state.amountForFreeDeliveryPaise, state.freeDeliveryProgress)
                            }
                        }
                    }
                    items(state.lines, key = { it.product.id }) { line ->
                        CartLineRow(line, onAdd = { viewModel.onAdd(line.product.id) }, onRemove = { viewModel.onRemove(line.product.id) })
                    }
                    if (state.suggestions.isNotEmpty()) {
                        item(key = "suggestions") {
                            Column {
                                SectionHeader("You might also like")
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.suggestions, key = { it.id }) { product ->
                                        ProductCard(
                                            product = product,
                                            quantity = quantities[product.id] ?: 0,
                                            onAdd = { viewModel.onAdd(product.id) },
                                            onRemove = { viewModel.onRemove(product.id) },
                                            onClick = { sheetProductId = product.id },
                                            modifier = Modifier.width(140.dp),
                                            emojiSize = 32,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "bill") { BillDetails(state.bill, Modifier.fillMaxWidth(), savingsPaise = state.savingsPaise) }
                    item(key = "policy") {
                        Text(
                            "Orders can't be cancelled once packed for delivery. Refunds for missing or damaged items are processed to the original payment method.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    state.suggestions.firstOrNull { it.id == sheetProductId }?.let { product ->
        ProductDetailSheet(
            product = product,
            quantity = quantities[product.id] ?: 0,
            onAdd = { viewModel.onAdd(product.id) },
            onRemove = { viewModel.onRemove(product.id) },
            onDismiss = { sheetProductId = null },
        )
    }
}

@Composable
private fun FreeDeliveryProgress(remainingPaise: Long, progress: Float) {
    val animated by animateFloatAsState(progress, label = "free-delivery")
    Text(
        if (remainingPaise > 0) "Add ${remainingPaise.formatRupees()} more for FREE delivery" else "🎉 Yay! You've unlocked FREE delivery",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth().height(6.dp))
}

@Composable
private fun CartLineRow(line: CartLine, onAdd: () -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line.product.emoji, fontSize = 32.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(line.product.name, fontWeight = FontWeight.SemiBold)
                Text(line.product.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.lineTotalPaise.formatRupees(), fontWeight = FontWeight.Bold)
                    if (line.lineSavingsPaise > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            (line.product.mrpPaise * line.quantity).formatRupees(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        )
                    }
                }
            }
            QuantityStepper(line.quantity, onAdd, onRemove)
        }
    }
}
