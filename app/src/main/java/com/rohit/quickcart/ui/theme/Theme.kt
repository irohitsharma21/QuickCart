package com.rohit.quickcart.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0E7C66)
private val TealDark = Color(0xFF5FD4B6)
private val Amber = Color(0xFFF2A900)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2EA),
    onPrimaryContainer = Color(0xFF00382D),
    secondary = Amber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFFFFE8B0),
    onSecondaryContainer = Color(0xFF2B1D00),
    background = Color(0xFFF7F8F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF3F1),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFD3F2EA),
    secondary = Amber,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFF5C4300),
    onSecondaryContainer = Color(0xFFFFE8B0),
)

@Composable
fun QuickCartTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
