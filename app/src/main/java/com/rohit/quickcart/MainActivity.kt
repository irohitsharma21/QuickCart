package com.rohit.quickcart

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rohit.quickcart.ui.navigation.QuickCartNavHost
import com.rohit.quickcart.ui.theme.QuickCartTheme
import dagger.hilt.android.AndroidEntryPoint

/** Single-activity app: every screen is a composable inside one NavHost. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QuickCartTheme {
                QuickCartNavHost()
            }
        }
    }
}
