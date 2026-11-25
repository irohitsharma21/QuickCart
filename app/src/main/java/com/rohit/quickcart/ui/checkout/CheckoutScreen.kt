package com.rohit.quickcart.ui.checkout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.ConnectivityObserver
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.domain.Bill
import com.rohit.quickcart.domain.PaymentMethod
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.ui.components.Banner
import com.rohit.quickcart.ui.components.BillDetails
import com.rohit.quickcart.util.formatRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CheckoutUiState(
    val address: String = "",
    val paymentMethod: PaymentMethod = PaymentMethod.UPI,
    val tipPaise: Long = 0,
    val bill: Bill = Bill(0, 0, 0),
    val itemCount: Int = 0,
    val isOnline: Boolean = true,
    val isPlacing: Boolean = false,
    val error: String? = null,
) {
    val addressError: String?
        get() = if (address.isNotEmpty() && address.trim().length < MIN_ADDRESS_LENGTH) "Please enter your full address" else null
    val canPlaceOrder: Boolean
        get() = address.trim().length >= MIN_ADDRESS_LENGTH && itemCount > 0 && !isPlacing

    companion object {
        const val MIN_ADDRESS_LENGTH = 10
    }
}

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    cartRepository: CartRepository,
    private val orderRepository: OrderRepository,
    connectivity: ConnectivityObserver,
) : ViewModel() {

    // SavedStateHandle: the typed address survives rotation AND process death
    // (Android killing the app in the background while the user is in another app).
    private val address = savedStateHandle.getStateFlow(KEY_ADDRESS, "")
    private val paymentMethod = savedStateHandle.getStateFlow(KEY_PAYMENT, PaymentMethod.UPI.name)
    private val tip = savedStateHandle.getStateFlow(KEY_TIP, 0L)
    private val placing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    /**
     * One-time events (navigate after the order is placed) go through a Channel, not state.
     * If "navigate" were state, it would fire again after rotation.
     */
    private val _orderPlaced = Channel<String>(Channel.BUFFERED)
    val orderPlaced: Flow<String> = _orderPlaced.receiveAsFlow()

    val uiState: StateFlow<CheckoutUiState> = combine(
        combine(address, paymentMethod, tip, ::Triple),
        cartRepository.observeCart(),
        connectivity.isOnline,
        placing,
        error,
    ) { (address, payment, tip), cart, online, isPlacing, error ->
        CheckoutUiState(
            address = address,
            paymentMethod = PaymentMethod.valueOf(payment),
            tipPaise = tip,
            bill = PricingRules.bill(cart, tip),
            itemCount = cart.sumOf { it.quantity },
            isOnline = online,
            isPlacing = isPlacing,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckoutUiState())

    fun onAddressChange(value: String) {
        savedStateHandle[KEY_ADDRESS] = value
    }

    fun onPaymentMethodChange(method: PaymentMethod) {
        savedStateHandle[KEY_PAYMENT] = method.name
    }

    fun onTipChange(tipPaise: Long) {
        savedStateHandle[KEY_TIP] = tipPaise
    }

    fun placeOrder() {
        val state = uiState.value
        if (!state.canPlaceOrder) return
        placing.value = true // also blocks double taps from placing two orders
        error.value = null
        viewModelScope.launch {
            try {
                val localId = orderRepository.placeOrder(state.address, state.paymentMethod, state.tipPaise)
                _orderPlaced.send(localId)
            } catch (e: IllegalStateException) {
                error.value = e.message ?: "Couldn't place the order"
            } finally {
                placing.value = false
            }
        }
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_PAYMENT = "payment"
        const val KEY_TIP = "tip"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(
    onBack: () -> Unit,
    onOrderPlaced: (localOrderId: String) -> Unit,
    viewModel: CheckoutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.orderPlaced.collect { localId -> onOrderPlaced(localId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Checkout", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = viewModel::placeOrder,
                    enabled = state.canPlaceOrder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (state.isPlacing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Place order · ${state.bill.totalPaise.formatRupees()}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!state.isOnline) {
                Banner("You're offline. We'll save your order and send it the moment you're back online.")
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Delivery address", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Flat / house no., street, area, city") },
                    isError = state.addressError != null,
                    supportingText = state.addressError?.let { { Text(it) } },
                    minLines = 2,
                    shape = RoundedCornerShape(12.dp),
                )

                Text("Payment", fontWeight = FontWeight.Bold)
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
                    Column {
                        PaymentMethod.entries.forEach { method ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = state.paymentMethod == method,
                                        onClick = { viewModel.onPaymentMethodChange(method) },
                                        role = Role.RadioButton,
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.paymentMethod == method, onClick = null)
                                Text(method.label, Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }

                Text("Tip your delivery partner", fontWeight = FontWeight.Bold)
                Text(
                    "Your kindness means a lot! 100% of the tip goes to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PricingRules.TIP_OPTIONS_PAISE.forEach { tip ->
                        FilterChip(
                            selected = state.tipPaise == tip,
                            onClick = { viewModel.onTipChange(tip) },
                            label = { Text(if (tip == 0L) "No tip" else "🙏 ${tip.formatRupees()}") },
                        )
                    }
                }

                BillDetails(state.bill, Modifier.fillMaxWidth())

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
