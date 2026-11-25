package com.rohit.quickcart.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import com.rohit.quickcart.BuildConfig
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.ui.components.EmptyState
import com.rohit.quickcart.ui.components.StatusPill
import com.rohit.quickcart.util.formatRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class OrdersUiState(val isLoading: Boolean = true, val orders: List<Order> = emptyList())

@HiltViewModel
class OrdersViewModel @Inject constructor(private val orderRepository: OrderRepository) : ViewModel() {
    val uiState: StateFlow<OrdersUiState> = orderRepository.observeOrders()
        .map { OrdersUiState(isLoading = false, orders = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersUiState())

    fun addDemoHistory() {
        viewModelScope.launch { orderRepository.addDemoHistory() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    onBack: () -> Unit,
    onOpenOrder: (localOrderId: String) -> Unit,
    viewModel: OrdersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My orders", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Debug builds only: seeds past orders so the "Running low?" prediction has history to learn from.
                    if (BuildConfig.DEBUG) TextButton(onClick = viewModel::addDemoHistory) { Text("+ Demo history") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            if (!state.isLoading && state.orders.isEmpty()) {
                EmptyState("🧾", "No orders yet", "Your orders will show up here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.orders, key = { it.localId }) { order ->
                        OrderRow(order, onClick = { onOpenOrder(order.localId) })
                    }
                }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

@Composable
private fun OrderRow(order: Order, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.lines.take(4).joinToString(" ") { it.emoji },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(order.status.label, highlighted = order.status != OrderStatus.CANCELLED)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${order.itemCount} item${if (order.itemCount == 1) "" else "s"} · ${order.bill.totalPaise.formatRupees()}",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(order.serverId?.let { "#$it" }, dateFormat.format(Date(order.createdAt))).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
