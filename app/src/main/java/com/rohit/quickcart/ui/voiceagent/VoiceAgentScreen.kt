package com.rohit.quickcart.ui.voiceagent

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rohit.quickcart.domain.voiceagent.AgentLanguage
import com.rohit.quickcart.ui.components.CartBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAgentScreen(
    onBack: () -> Unit,
    onOpenCart: () -> Unit,
    onOpenCheckout: () -> Unit,
    onOrderPlaced: (localOrderId: String) -> Unit,
    onPasteList: () -> Unit,
    viewModel: VoiceAgentViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Microphone permission: asked once on entry. If refused, the agent still talks and you can type.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.start(micAllowed = granted)
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.start(micAllowed = true) else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { destination ->
            when (destination) {
                AgentNavigation.Cart -> onOpenCart()
                AgentNavigation.Checkout -> onOpenCheckout()
                is AgentNavigation.Tracking -> onOrderPlaced(destination.localOrderId)
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Talk to QuickCart", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = onPasteList) { Text("Paste a list") } },
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                CartBar(state.cart.itemCount, state.cart.totalPaise, state.cart.previewEmojis, onOpenCart)
                TypeInsteadBar(onSend = viewModel::onTyped)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == AgentMode.GEMINI,
                    onClick = { viewModel.onModeChange(AgentMode.GEMINI) },
                    enabled = state.geminiAvailable,
                    label = { Text("✨ Gemini AI") },
                )
                FilterChip(selected = state.mode == AgentMode.OFFLINE, onClick = { viewModel.onModeChange(AgentMode.OFFLINE) }, label = { Text("Offline") })
                if (state.mode == AgentMode.OFFLINE) {
                    FilterChip(selected = state.language == AgentLanguage.ENGLISH, onClick = { viewModel.onLanguageChange(AgentLanguage.ENGLISH) }, label = { Text("EN") })
                    FilterChip(selected = state.language == AgentLanguage.HINDI, onClick = { viewModel.onLanguageChange(AgentLanguage.HINDI) }, label = { Text("हिंदी") })
                }
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.messages) { _, message -> ChatBubble(message) }
                if (state.partial.isNotBlank()) {
                    item { ChatBubble(ChatMessage(fromUser = true, text = state.partial), live = true) }
                }
            }

            AgentOrb(state.phase, onTap = viewModel::onMicTap)
            Text(
                statusText(state),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 12.dp),
            )
        }
    }
}

private fun statusText(state: VoiceAgentUiState): String = when {
    state.mode == AgentMode.GEMINI && state.phase == AgentPhase.CONNECTING -> "Connecting to Gemini…"
    state.mode == AgentMode.GEMINI && state.phase == AgentPhase.LIVE -> "✨ Live: talk naturally · tap ✨ to interrupt"
    state.mode == AgentMode.GEMINI -> "Tap to reconnect"
    !state.recognitionAvailable -> "Voice input isn't available on this device. Type below."
    !state.micAllowed -> "Microphone is off. Type below, or allow it in Settings."
    else -> when (state.phase) {
        AgentPhase.SPEAKING -> "Speaking… tap to interrupt"
        AgentPhase.LISTENING -> "Listening… say \"ek kilo doodh\""
        AgentPhase.THINKING -> "Got it…"
        AgentPhase.IDLE -> "Tap the mic to talk"
        AgentPhase.CONNECTING, AgentPhase.LIVE -> "Connecting…"
    }
}

/** The big animated circle: pulses while listening, breathes while speaking. */
@Composable
private fun AgentOrb(phase: AgentPhase, onTap: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (phase == AgentPhase.LISTENING || phase == AgentPhase.LIVE) 1.35f else if (phase == AgentPhase.SPEAKING) 1.15f else 1f,
        animationSpec = infiniteRepeatable(tween(if (phase == AgentPhase.LISTENING) 700 else 1_000), RepeatMode.Reverse),
        label = "pulse",
    )
    val ringColor = if (phase == AgentPhase.LISTENING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(110.dp).scale(pulse).alpha(0.25f).clip(CircleShape).background(ringColor))
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClickLabel = "Talk", onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                AgentPhase.LIVE -> Text("✨", fontSize = 38.sp)
                AgentPhase.THINKING, AgentPhase.CONNECTING -> CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
                AgentPhase.SPEAKING -> Text("🔊", fontSize = 38.sp)
                else -> Text("🎤", fontSize = 38.sp)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, live: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (message.fromUser) 16.dp else 4.dp,
                bottomEnd = if (message.fromUser) 4.dp else 16.dp,
            ),
            color = if (message.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp).alpha(if (live) 0.6f else 1f),
        ) {
            Text(
                (if (message.fromUser) "" else "🛒 ") + message.text,
                color = if (message.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = if (live) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TypeInsteadBar(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("…or type: 2 doodh aur ek bread") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.size(8.dp))
            IconButton(onClick = send, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
