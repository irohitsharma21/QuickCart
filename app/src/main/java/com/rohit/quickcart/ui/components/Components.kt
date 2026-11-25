package com.rohit.quickcart.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohit.quickcart.domain.Bill
import com.rohit.quickcart.domain.PricingRules
import com.rohit.quickcart.domain.Product
import com.rohit.quickcart.util.formatRupees
import kotlinx.coroutines.delay

@Composable
fun ProductCard(
    product: Product,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emojiSize: Int = 44,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(product.emoji, fontSize = emojiSize.sp)
                if (product.discountPercent > 0) DiscountTag(product.discountPercent, Modifier.align(Alignment.TopStart))
            }
            Spacer(Modifier.height(6.dp))
            DeliveryTimeTag()
            Spacer(Modifier.height(4.dp))
            Text(
                product.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(product.unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriceColumn(product, Modifier.weight(1f))
                AddOrStepper(product, quantity, onAdd, onRemove)
            }
        }
    }
}

@Composable
private fun DiscountTag(percent: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$percent% OFF",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
            .padding(6.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun DeliveryTimeTag() {
    Text(
        "⏱ 10 MINS",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun PriceColumn(product: Product, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(product.pricePaise.formatRupees(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        if (product.mrpPaise > product.pricePaise) {
            Text(
                product.mrpPaise.formatRupees(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = TextDecoration.LineThrough,
            )
        }
    }
}

/** "ADD" turns into a "− 1 +" stepper with a small animation and a light haptic tick. */
@Composable
fun AddOrStepper(product: Product, quantity: Int, onAdd: () -> Unit, onRemove: () -> Unit, wide: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    val add = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onAdd()
    }
    if (!product.inStock) {
        Text("Out of stock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    AnimatedContent(
        targetState = quantity == 0,
        transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.85f)) togetherWith fadeOut() },
        label = "add-stepper",
    ) { showAdd ->
        if (showAdd) {
            OutlinedButton(
                onClick = add,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(if (wide) 44.dp else 34.dp)
                    .then(if (wide) Modifier.width(140.dp) else Modifier),
            ) { Text("ADD", fontWeight = FontWeight.Bold) }
        } else {
            QuantityStepper(quantity, add, onRemove, height = if (wide) 44.dp else 34.dp, width = if (wide) 140.dp else null)
        }
    }
}

@Composable
fun QuantityStepper(
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    width: Dp? = null,
) {
    Row(
        modifier = modifier
            .height(height)
            .then(if (width != null) Modifier.width(width) else Modifier)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        StepperButton("−", "Remove one", onRemove, size = height)
        AnimatedContent(
            targetState = quantity,
            transitionSpec = {
                val up = targetState > initialState
                (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
            },
            label = "qty",
        ) { q ->
            Text("$q", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp), textAlign = TextAlign.Center)
        }
        StepperButton("+", "Add one", onAdd, enabled = quantity < PricingRules.MAX_QUANTITY_PER_ITEM, size = height)
    }
}

@Composable
private fun StepperButton(symbol: String, description: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 1f else 0.4f),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

/** Product details in a bottom sheet: opens on tapping any product card. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailSheet(
    product: Product,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(product.emoji, fontSize = 96.sp)
                if (product.discountPercent > 0) DiscountTag(product.discountPercent, Modifier.align(Alignment.TopStart))
            }
            Spacer(Modifier.height(16.dp))
            DeliveryTimeTag()
            Spacer(Modifier.height(6.dp))
            Text(product.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(product.unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (product.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(product.description, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(product.pricePaise.formatRupees(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (product.mrpPaise > product.pricePaise) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "MRP ${product.mrpPaise.formatRupees()}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = TextDecoration.LineThrough,
                            )
                        }
                    }
                    Text("Inclusive of all taxes", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AddOrStepper(product, quantity, onAdd, onRemove, wide = true)
            }
        }
    }
}

/**
 * Floating "cart" bar: small thumbnails of the last items added, count and total.
 * Shown on every browsing screen so checkout is always one tap away.
 */
@Composable
fun CartBar(itemCount: Int, totalPaise: Long, previewEmojis: List<String>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = itemCount > 0,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width((28 + 18 * (previewEmojis.size - 1).coerceAtLeast(0)).dp).height(32.dp)) {
                previewEmojis.forEachIndexed { index, emoji ->
                    Box(
                        Modifier
                            .offset(x = (18 * index).dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(emoji, fontSize = 16.sp) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$itemCount item${if (itemCount == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(totalPaise.formatRupees(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Text("View cart", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** A search box that isn't a text field: it shows a rotating hint and opens the search screen. */
@Composable
fun SearchLauncher(onClick: () -> Unit, modifier: Modifier = Modifier, onMicClick: (() -> Unit)? = null) {
    val hints = remember { listOf("milk", "atta", "chips", "eggs", "bread", "banana") }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_500)
            index = (index + 1) % hints.size
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text("Search ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(
                targetState = hints[index],
                transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                label = "hint",
            ) { hint -> Text("\"$hint\"", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            if (onMicClick != null) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(onClickLabel = "Order by voice", onClick = onMicClick),
                    contentAlignment = Alignment.Center,
                ) { Text("🎤", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
fun BillDetails(bill: Bill, modifier: Modifier = Modifier, savingsPaise: Long = 0) {
    Surface(modifier = modifier, shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Bill details", fontWeight = FontWeight.Bold)
            BillRow("Item total", bill.itemTotalPaise.formatRupees())
            BillRow("Delivery fee", if (bill.deliveryFeePaise == 0L) "FREE" else bill.deliveryFeePaise.formatRupees())
            BillRow("Handling fee", bill.handlingFeePaise.formatRupees())
            if (bill.tipPaise > 0) BillRow("Delivery partner tip", bill.tipPaise.formatRupees())
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            BillRow("To pay", bill.totalPaise.formatRupees(), bold = true)
            if (savingsPaise > 0) {
                Text(
                    "You saved ${savingsPaise.formatRupees()} on this order",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun BillRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun EmptyState(emoji: String, title: String, subtitle: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(emoji, fontSize = 56.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, highlighted: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun PrimaryBottomButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (loading) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(text, fontWeight = FontWeight.Bold)
            }
        }
    }
}
