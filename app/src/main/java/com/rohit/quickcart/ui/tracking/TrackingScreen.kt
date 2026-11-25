package com.rohit.quickcart.ui.tracking

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.LiveConnection
import com.rohit.quickcart.data.repository.OrderRepository
import com.rohit.quickcart.domain.Order
import com.rohit.quickcart.domain.OrderStatus
import com.rohit.quickcart.ui.components.BillDetails
import com.rohit.quickcart.ui.components.StatusPill
import com.rohit.quickcart.util.formatRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TrackingUiState(
    val isLoading: Boolean = true,
    val order: Order? = null,
    val connection: LiveConnection = LiveConnection.Connecting,
)

@HiltViewModel
class TrackingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    orderRepository: OrderRepository,
) : ViewModel() {

    /** Navigation puts the route's fields into SavedStateHandle (see TrackingRoute). */
    val localOrderId: String = checkNotNull(savedStateHandle["localOrderId"]) { "localOrderId missing" }

    /**
     * Two streams:
     * - the order itself, read from Room (the single source of truth)
     * - the socket's connection state. Collecting it is what keeps the socket open, and every
     *   message the socket receives is written into Room, which updates the first stream.
     *
     * WhileSubscribed(5_000) + collectAsStateWithLifecycle make this lifecycle-aware:
     * app in background -> UI stops collecting -> after 5 s the upstream is cancelled -> socket closes.
     * Rotation takes < 5 s, so it does NOT reconnect.
     */
    val uiState: StateFlow<TrackingUiState> = combine(
        orderRepository.observeOrder(localOrderId),
        orderRepository.trackOrder(localOrderId).onStart { emit(LiveConnection.Connecting) },
    ) { order, connection -> TrackingUiState(isLoading = false, order = order, connection = connection) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackingUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackingScreen(
    onBack: () -> Unit,
    justPlaced: Boolean = false,
    viewModel: TrackingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // rememberSaveable: the celebration plays once, not again after rotation.
    var showCelebration by rememberSaveable { mutableStateOf(justPlaced) }
    if (showCelebration) {
        LaunchedEffect(Unit) {
            delay(2_000)
            showCelebration = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order status", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        val order = state.order
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            order == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Order not found")
            }

            else -> TrackingContent(order, state.connection, Modifier.padding(padding))
        }
    }
    OrderPlacedCelebration(showCelebration)
}

@Composable
private fun OrderPlacedCelebration(visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        val scale = remember { Animatable(0.3f) }
        LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 200f)) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(120.dp).scale(scale.value).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 64.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(24.dp))
                Text("Order placed!", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.ExtraBold)
                Text("Arriving in about 10 minutes", color = Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/**
 * A stylised map: a curved road from the store to your home, drawn on a Canvas.
 * The rider (🛵) moves along the road using PathMeasure, and the part already travelled is coloured.
 * Progress is animated, so the rider glides smoothly between the server's 2-second updates.
 */
@Composable
private fun RouteMap(progress: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress, animationSpec = tween(1_800, easing = LinearEasing), label = "rider")
    val textMeasurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val road = MaterialTheme.colorScheme.outlineVariant
    val block = MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // City blocks in the background.
        val cell = size.width / 6
        for (x in 0 until 6) for (y in 0 until 4) {
            drawRoundRect(
                color = block,
                topLeft = Offset(x * cell + 6.dp.toPx(), y * (size.height / 4) + 6.dp.toPx()),
                size = Size(cell - 12.dp.toPx(), size.height / 4 - 12.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
        }
        val start = Offset(size.width * 0.12f, size.height * 0.78f)
        val end = Offset(size.width * 0.88f, size.height * 0.22f)
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(size.width * 0.45f, size.height * 1.05f, size.width * 0.55f, -size.height * 0.05f, end.x, end.y)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val stroke = 6.dp.toPx()
        drawPath(path, road, style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))))
        val travelled = Path()
        measure.getSegment(0f, measure.length * animated, travelled, true)
        drawPath(travelled, primary, style = Stroke(stroke, cap = StrokeCap.Round))

        fun emoji(text: String, at: Offset, sizeSp: Int) {
            val layout = textMeasurer.measure(text, TextStyle(fontSize = sizeSp.sp))
            drawText(layout, topLeft = Offset(at.x - layout.size.width / 2, at.y - layout.size.height / 2))
        }
        emoji("🏪", start, 26)
        emoji("🏠", end, 26)
        emoji("🛵", measure.getPosition(measure.length * animated), 28)
    }
}

@Composable
private fun TrackingContent(order: Order, connection: LiveConnection, modifier: Modifier = Modifier) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(order.status.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ConnectionChip(connection)
                }
                Spacer(Modifier.height(4.dp))
                Text(headline(order), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (order.status != OrderStatus.PENDING_SYNC && order.status != OrderStatus.CANCELLED) {
            RouteMap(order.riderProgress)
            if (order.status == OrderStatus.OUT_FOR_DELIVERY) {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧑‍✈️", fontSize = 28.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Your delivery partner is on the way", fontWeight = FontWeight.SemiBold)
                            Text("They'll call if they need directions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        StatusTimeline(order.status)

        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Items", fontWeight = FontWeight.Bold)
                order.lines.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("${line.name} × ${line.quantity}", Modifier.weight(1f))
                        Text((line.pricePaise * line.quantity).formatRupees())
                    }
                }
                Text(
                    "Deliver to: ${order.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Payment: ${order.paymentMethod.label}" + (order.serverId?.let { " · Order #$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        BillDetails(order.bill, Modifier.fillMaxWidth())
    }
}

private fun headline(order: Order): String = when (order.status) {
    OrderStatus.PENDING_SYNC -> "Saved on your phone. It will be sent as soon as you're online."
    OrderStatus.DELIVERED -> "Delivered. Enjoy!"
    OrderStatus.CANCELLED -> "This order was cancelled."
    else -> order.etaMinutes?.let { "Arriving in $it min" } ?: "Confirming your order…"
}

@Composable
private fun ConnectionChip(connection: LiveConnection) {
    val (text, color) = when (connection) {
        LiveConnection.Connecting -> "Connecting…" to Color(0xFF9E9E9E)
        LiveConnection.Live -> "● Live" to Color(0xFF2E7D32)
        is LiveConnection.Reconnecting -> "Reconnecting in ${(connection.retryInMs + 999) / 1000}s" to Color(0xFFF57C00)
        LiveConnection.WaitingForSync -> "Offline" to Color(0xFFF57C00)
        LiveConnection.Finished -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

private val timelineSteps = listOf(
    OrderStatus.PLACED to "Order placed",
    OrderStatus.PACKED to "Packed",
    OrderStatus.OUT_FOR_DELIVERY to "Out for delivery",
    OrderStatus.DELIVERED to "Delivered",
)

@Composable
private fun StatusTimeline(status: OrderStatus) {
    if (status == OrderStatus.CANCELLED) {
        StatusPill("Cancelled", highlighted = false)
        return
    }
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            timelineSteps.forEach { (step, label) ->
                val done = status != OrderStatus.PENDING_SYNC && status.ordinal >= step.ordinal
                val current = status == step
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .background(
                                if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
