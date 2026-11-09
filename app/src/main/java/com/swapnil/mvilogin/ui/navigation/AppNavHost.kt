package com.swapnil.mvilogin.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.swapnil.mvilogin.ui.detail.ProductDetailRoute
import com.swapnil.mvilogin.ui.home.HomeRoute
import com.swapnil.mvilogin.ui.login.LoginRoute
import kotlinx.serialization.Serializable

/*
 * TYPE-SAFE NAVIGATION (Navigation 2.8+): routes are @Serializable classes, not strings.
 * "detail/{id}" typos and wrong argument types become compile errors.
 * Arguments are written into the destination's SavedStateHandle under the property names,
 * so they survive process death.
 *
 * Each destination gets its OWN ViewModelStore: LoginViewModel (holding the password)
 * is cleared as soon as Login is popped off the back stack.
 */

@Serializable
data class LoginDestination(val sessionExpired: Boolean = false) {
    companion object {
        const val ARG_SESSION_EXPIRED = "sessionExpired"
    }
}

@Serializable
data object HomeDestination

@Serializable
data class ProductDetailDestination(val id: Int) {
    companion object {
        const val ARG_ID = "id"
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = LoginDestination()) {

        composable<LoginDestination> {
            LoginRoute(
                onLoggedIn = {
                    navController.navigate(HomeDestination) {
                        // Clear the whole back stack: Back on Home must exit, not return to Login.
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable<HomeDestination> {
            HomeRoute(
                onOpenProduct = { id -> navController.navigate(ProductDetailDestination(id)) },
                onLoggedOut = { expired ->
                    navController.navigate(LoginDestination(sessionExpired = expired)) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
            )
        }

        composable<ProductDetailDestination> {
            ProductDetailRoute(onBack = { navController.popBackStack() })
        }
    }
}
