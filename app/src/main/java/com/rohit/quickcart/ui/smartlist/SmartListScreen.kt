package com.rohit.quickcart.ui.smartlist

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rohit.quickcart.data.repository.CartRepository
import com.rohit.quickcart.data.repository.CatalogRepository
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.domain.smartlist.GroceryListParser
import com.rohit.quickcart.domain.smartlist.ParsedItem
import com.rohit.quickcart.ui.components.PrimaryBottomButton
import com.rohit.quickcart.ui.components.QuantityStepper
import com.rohit.quickcart.util.formatRupees
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A parsed item plus the user's edits (changed quantity, or picked a different product). */
data class ReviewLine(val item: ParsedItem, val product: Product?, val quantity: Int) {
    val isMatched: Boolean get() = product != null
}

data class SmartListUiState(
    val text: String = "",
    val lines: List<ReviewLine> = emptyList(),
) {
    val matched: List<ReviewLine> get() = lines.filter { it.isMatched && it.quantity > 0 }
    val totalPaise: Long get() = matched.sumOf { it.product!!.pricePaise * it.quantity }
    val itemCount: Int get() = matched.sumOf { it.quantity }
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SmartListViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    catalogRepository: CatalogRepository,
    private val cartRepository: CartRepository,
) : ViewModel() {

    private val text = savedStateHandle.getStateFlow(KEY_TEXT, "")

    /** Edits the user made in the review list, keyed by the item's raw text. */
    private val quantityEdits = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val productPicks = MutableStateFlow<Map<String, Product>>(emptyMap())

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = _done.receiveAsFlow()

    private val parser = catalogRepository.observeProducts(null, "").map { GroceryListParser(it) }

    val uiState: StateFlow<SmartListUiState> = combine(
        text,
        text.debounce(300), // re-parse once typing pauses
        parser,
        quantityEdits,
        productPicks,
    ) { currentText, settledText, parser, edits, picks ->
        val lines = parser.parse(settledText).map { item ->
            val product = picks[item.raw] ?: item.product
            ReviewLine(item, product, edits[item.raw] ?: item.quantity)
        }
        SmartListUiState(currentText, lines)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SmartListUiState())

    fun onTextChange(value: String) {
        savedStateHandle[KEY_TEXT] = value
    }

    /** Speech result: appended, so the user can speak in several goes. */
    fun onSpeechResult(spoken: String) {
        val current = text.value.trim()
        onTextChange(if (current.isEmpty()) spoken else "$current, $spoken")
    }

    fun onQuantityChange(line: ReviewLine, delta: Int) {
        val newQuantity = (line.quantity + delta).coerceIn(0, GroceryListParser.MAX_QTY)
        quantityEdits.value = quantityEdits.value + (line.item.raw to newQuantity)
    }

    fun onPickSuggestion(line: ReviewLine, product: Product) {
        productPicks.value = productPicks.value + (line.item.raw to product)
    }

    fun addAllToCart() {
        val toAdd = uiState.value.matched
        if (toAdd.isEmpty()) return
        viewModelScope.launch {
            toAdd.forEach { cartRepository.add(it.product!!.id, it.quantity) }
            onTextChange("")
            quantityEdits.value = emptyMap()
            productPicks.value = emptyMap()
            _done.send(Unit)
        }
    }

    private companion object {
        const val KEY_TEXT = "smartListText"
    }
}

private val examples = listOf(
    "do doodh, ek bread aur aadha kilo pyaaz",
    "दो दूध और एक दर्जन अंडे",
    "1kg atta\n2 packet chips\ncold coffee",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartListScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: SmartListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.done.collect { onDone() } }

    // The system speech recogniser: no extra permission needed, it shows its own mic UI.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(viewModel::onSpeechResult)
        }
    }
    fun listen(language: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your grocery list")
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("Voice input isn't available on this device. Type or paste your list instead.") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Order by voice or list", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.itemCount > 0) {
                PrimaryBottomButton("Add ${state.itemCount} items to cart · ${state.totalPaise.formatRupees()}", onClick = viewModel::addAllToCart)
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "mic") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MicButton("English / Hinglish", Modifier.weight(1f)) { listen("en-IN") }
                    MicButton("हिंदी", Modifier.weight(1f)) { listen("hi-IN") }
                }
            }
            item(key = "input") {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("…or type / paste your list") },
                    placeholder = { Text("2 doodh, 1 bread, aadha kilo pyaaz") },
                    minLines = 3,
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (state.text.isNotEmpty()) IconButton(onClick = { viewModel.onTextChange("") }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                )
            }
            if (state.text.isBlank()) {
                item(key = "examples") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Try one:", style = MaterialTheme.typography.labelLarge)
                        examples.forEach { example ->
                            AssistChip(onClick = { viewModel.onTextChange(example) }, label = { Text(example.replace("\n", ", ")) })
                        }
                        Text(
                            "Understands English, Hinglish and Hindi: numbers (do, teen, दो), units (kilo, packet, dozen) " +
                                "and everyday names (doodh, pyaaz, अंडे). It runs on your phone and works offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "review-title") {
                    Text("We understood ${state.lines.count { it.isMatched }} of ${state.lines.size} items", fontWeight = FontWeight.Bold)
                }
                items(state.lines, key = { it.item.raw }) { line ->
                    ReviewRow(line, onQuantity = { viewModel.onQuantityChange(line, it) }, onPick = { viewModel.onPickSuggestion(line, it) })
                }
            }
        }
    }
}

@Composable
private fun MicButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale",
    )
    Surface(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).clickable(onClickLabel = "Speak in $label", onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(vertical = 18.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(60.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("🎤", fontSize = 28.sp) }
            Spacer(Modifier.height(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun ReviewRow(line: ReviewLine, onQuantity: (Int) -> Unit, onPick: (Product) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("\"${line.item.raw}\"", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val product = line.product
            if (product != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(product.emoji, fontSize = 30.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(product.name, fontWeight = FontWeight.SemiBold)
                        Text("${product.unit} · ${product.pricePaise.formatRupees()}", style = MaterialTheme.typography.bodySmall)
                        if (line.item.needsReview && line.item.product == product) {
                            Text("Is this right?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    if (!product.inStock) {
                        Text("Out of stock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        QuantityStepper(line.quantity, onAdd = { onQuantity(+1) }, onRemove = { onQuantity(-1) })
                    }
                }
            } else {
                Text("Couldn't find this", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
            }
            val suggestions = line.item.suggestions.filter { it != product }
            if (suggestions.isNotEmpty() && (product == null || line.item.needsReview)) {
                Spacer(Modifier.height(6.dp))
                Text("Did you mean:", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        FilterChip(selected = false, onClick = { onPick(s) }, label = { Text("${s.emoji} ${s.name}") })
                    }
                }
            }
        }
    }
}
