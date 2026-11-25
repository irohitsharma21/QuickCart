package com.rohit.quickcart.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.rohit.quickcart.ui.cart.CartScreen
import com.rohit.quickcart.ui.category.CategoryScreen
import com.rohit.quickcart.ui.checkout.CheckoutScreen
import com.rohit.quickcart.ui.home.HomeNavigation
import com.rohit.quickcart.ui.home.HomeScreen
import com.rohit.quickcart.ui.orders.OrdersScreen
import com.rohit.quickcart.ui.search.SearchScreen
import com.rohit.quickcart.ui.smartlist.SmartListScreen
import com.rohit.quickcart.ui.tracking.TrackingScreen
import com.rohit.quickcart.ui.voiceagent.VoiceAgentScreen
import kotlinx.serialization.Serializable

/*
 * Type-safe routes (Navigation 2.8+). A route is a @Serializable class, so arguments are
 * compile-time checked instead of being glued into strings like "tracking/{id}".
 */
@Serializable data object HomeRoute
@Serializable data object SearchRoute
@Serializable data class CategoryRoute(val categoryId: String)
@Serializable data object SmartListRoute
@Serializable data object VoiceAgentRoute
@Serializable data object CartRoute
@Serializable data object CheckoutRoute
@Serializable data object OrdersRoute
@Serializable data class TrackingRoute(val localOrderId: String, val justPlaced: Boolean = false)

@Composable
fun QuickCartNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = HomeRoute,
        enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
    ) {
        composable<HomeRoute> {
            HomeScreen(
                HomeNavigation(
                    openCart = { navController.navigate(CartRoute) },
                    openOrders = { navController.navigate(OrdersRoute) },
                    openSearch = { navController.navigate(SearchRoute) },
                    openCategory = { navController.navigate(CategoryRoute(it)) },
                    openVoiceOrder = { navController.navigate(VoiceAgentRoute) },
                ),
            )
        }

        composable<SearchRoute> {
            SearchScreen(onBack = { navController.popBackStack() }, onOpenCart = { navController.navigate(CartRoute) })
        }

        composable<CategoryRoute> {
            CategoryScreen(
                onBack = { navController.popBackStack() },
                onOpenSearch = { navController.navigate(SearchRoute) },
                onOpenCart = { navController.navigate(CartRoute) },
            )
        }

        composable<VoiceAgentRoute> {
            VoiceAgentScreen(
                onBack = { navController.popBackStack() },
                onOpenCart = { navController.navigate(CartRoute) { popUpTo(HomeRoute) } },
                onOpenCheckout = { navController.navigate(CheckoutRoute) { popUpTo(HomeRoute) } },
                onOrderPlaced = { localId -> navController.navigate(TrackingRoute(localId, justPlaced = true)) { popUpTo(HomeRoute) } },
                onPasteList = { navController.navigate(SmartListRoute) },
            )
        }

        composable<SmartListRoute> {
            SmartListScreen(
                onBack = { navController.popBackStack() },
                // After adding, go straight to the cart (and Back returns home, not to the list).
                onDone = { navController.navigate(CartRoute) { popUpTo(HomeRoute) } },
            )
        }

        composable<CartRoute> {
            CartScreen(
                onBack = { navController.popBackStack() },
                onCheckout = { navController.navigate(CheckoutRoute) },
            )
        }

        composable<CheckoutRoute> {
            CheckoutScreen(
                onBack = { navController.popBackStack() },
                onOrderPlaced = { localId ->
                    // Drop Cart + Checkout from the back stack: "back" from tracking goes home, not to an empty cart.
                    navController.navigate(TrackingRoute(localId, justPlaced = true)) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }

        composable<OrdersRoute> {
            OrdersScreen(
                onBack = { navController.popBackStack() },
                onOpenOrder = { localId -> navController.navigate(TrackingRoute(localId)) },
            )
        }

        composable<TrackingRoute> { entry ->
            TrackingScreen(
                onBack = { navController.popBackStack() },
                justPlaced = entry.toRoute<TrackingRoute>().justPlaced,
            )
        }
    }
}
