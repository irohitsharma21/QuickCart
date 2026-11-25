package com.rohit.quickcart.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohit.quickcart.domain.Category
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.ui.components.Banner
import com.rohit.quickcart.ui.components.CartBar
import com.rohit.quickcart.ui.components.EmptyState
import com.rohit.quickcart.ui.components.ProductCard
import com.rohit.quickcart.ui.components.ProductDetailSheet
import com.rohit.quickcart.ui.components.SearchLauncher
import com.rohit.quickcart.ui.components.SectionHeader
import kotlinx.coroutines.delay

/** Callbacks the home screen needs from navigation. */
data class HomeNavigation(
    val openCart: () -> Unit,
    val openOrders: () -> Unit,
    val openSearch: () -> Unit,
    val openCategory: (categoryId: String) -> Unit,
    val openVoiceOrder: () -> Unit,
)

/** Stateful wrapper: gets the ViewModel and passes plain state + callbacks down. */
@Composable
fun HomeScreen(nav: HomeNavigation, viewModel: HomeViewModel = hiltViewModel()) {
    // Lifecycle-aware: stops collecting when the app goes to the background.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state, nav, onAdd = viewModel::onAdd, onRemove = viewModel::onRemove, onRefresh = viewModel::refresh)
}

/** Stateless: everything comes in as parameters, which makes it easy to preview and test. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    nav: HomeNavigation,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    // Which product's detail sheet is open. Only the id is saved, so it survives rotation.
    var sheetProductId by rememberSaveable { mutableStateOf<String?>(null) }
    val allProducts = state.rails.flatMap { it.products } + state.reorder.map { it.product }

    Scaffold(
        bottomBar = { CartBar(state.cart.itemCount, state.cart.totalPaise, state.cart.previewEmojis, nav.openCart) },
    ) { padding ->
        when {
            state.showFullScreenLoader -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

            state.showFullScreenError -> EmptyState(
                emoji = "📡",
                title = "No connection",
                subtitle = state.refreshError.orEmpty(),
                action = { Button(onClick = onRefresh) { Text("Try again") } },
            )

            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing && state.hasCatalog,
                onRefresh = onRefresh,
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") { Header(state.deliveryAddress, nav.openOrders) }

                    // Stays pinned to the top while the user scrolls.
                    stickyHeader(key = "search") {
                        Box(
                            Modifier
                                .background(MaterialTheme.colorScheme.background)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { SearchLauncher(onClick = nav.openSearch, onMicClick = nav.openVoiceOrder) }
                    }

                    if (!state.isOnline) {
                        item(key = "offline") { Banner("You're offline. Your cart is saved, and orders will be sent when you're back online.") }
                    } else if (state.refreshError != null) {
                        item(key = "error") { Banner(state.refreshError) }
                    }

                    item(key = "offers") { OffersCarousel(onOfferClick = nav.openCategory) }

                    item(key = "voice") { VoiceOrderCard(onClick = nav.openVoiceOrder) }

                    if (state.reorder.isNotEmpty()) {
                        item(key = "reorder") {
                            ReorderRail(state.reorder, state.cart.quantities, onAdd, onRemove, onOpen = { sheetProductId = it })
                        }
                    }

                    item(key = "categories") { CategoryGrid(state.categories, nav.openCategory) }

                    items(state.rails, key = { "rail-${it.category.id}" }) { rail ->
                        ProductRail(
                            title = rail.category.name,
                            products = rail.products,
                            quantities = state.cart.quantities,
                            onSeeAll = { nav.openCategory(rail.category.id) },
                            onAdd = onAdd,
                            onRemove = onRemove,
                            onOpen = { sheetProductId = it },
                        )
                    }
                }
            }
        }
    }

    allProducts.firstOrNull { it.id == sheetProductId }?.let { product ->
        ProductDetailSheet(
            product = product,
            quantity = state.cart.quantities[product.id] ?: 0,
            onAdd = { onAdd(product.id) },
            onRemove = { onRemove(product.id) },
            onDismiss = { sheetProductId = null },
        )
    }
}

@Composable
private fun Header(address: String?, onOpenOrders: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.background)),
            )
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QuickCart in", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("10 minutes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        address?.let { "Home · $it" } ?: "Add your address at checkout",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                IconButton(onClick = onOpenOrders) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "My orders") }
            }
        }
    }
}

private data class Offer(val title: String, val subtitle: String, val emoji: String, val categoryId: String, val colors: List<Color>)

private val offers = listOf(
    Offer("Fresh fruits", "Up to 20% off this week", "🍎", "fruits-veg", listOf(Color(0xFF0E7C66), Color(0xFF34A88A))),
    Offer("Breakfast essentials", "Milk, bread, eggs in 10 mins", "🍳", "dairy", listOf(Color(0xFFE08A00), Color(0xFFF2B33D))),
    Offer("Movie night", "Snacks & drinks combo deals", "🍿", "snacks", listOf(Color(0xFF5B4BC4), Color(0xFF8A7CE8))),
)

/** Auto-advancing offers banner (HorizontalPager). */
@Composable
private fun OffersCarousel(onOfferClick: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { offers.size })
    LaunchedEffect(pagerState) {
        while (true) {
            delay(4_000)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % offers.size)
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 10.dp) { page ->
            val offer = offers[page]
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.horizontalGradient(offer.colors))
                    .clickable { onOfferClick(offer.categoryId) }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(offer.title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(offer.subtitle, color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Order now ›",
                        color = offer.colors.first(),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.background(Color.White, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Text(offer.emoji, fontSize = 56.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(offers.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = if (i == pagerState.currentPage) 16.dp else 6.dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun VoiceOrderCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Text("🎤", fontSize = 24.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Just say it, or paste your list", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "\"Do doodh, ek bread aur aadha kilo pyaaz\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text("NEW", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun ReorderRail(
    items: List<ReorderItem>,
    quantities: Map<String, Int>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val anyPrediction = items.any { it.isPrediction }
    Column(Modifier.padding(top = 8.dp)) {
        SectionHeader(if (anyPrediction) "Running low?" else "Buy again", Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.product.id }) { item ->
                Column(Modifier.width(150.dp)) {
                    ProductCard(
                        product = item.product,
                        quantity = quantities[item.product.id] ?: 0,
                        onAdd = { onAdd(item.product.id) },
                        onRemove = { onRemove(item.product.id) },
                        onClick = { onOpen(item.product.id) },
                        emojiSize = 36,
                    )
                    Text(
                        (if (item.isPrediction) "🧠 " else "") + item.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryGrid(categories: List<Category>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp)) {
        SectionHeader("Shop by category")
        categories.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { category ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpen(category.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.2f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center,
                        ) { Text(category.emoji, fontSize = 40.sp) }
                        Text(
                            category.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            minLines = 2,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ProductRail(
    title: String,
    products: List<Product>,
    quantities: Map<String, Int>,
    onSeeAll: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp)) {
        SectionHeader(title, Modifier.padding(start = 16.dp, end = 4.dp), action = "See all ›", onAction = onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Stable keys: Compose can move/skip items instead of redrawing the whole row.
            items(products, key = { it.id }) { product ->
                ProductCard(
                    product = product,
                    quantity = quantities[product.id] ?: 0,
                    onAdd = { onAdd(product.id) },
                    onRemove = { onRemove(product.id) },
                    onClick = { onOpen(product.id) },
                    modifier = Modifier.width(150.dp),
                    emojiSize = 36,
                )
            }
        }
    }
}
