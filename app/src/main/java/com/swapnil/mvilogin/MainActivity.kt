package com.swapnil.mvilogin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.swapnil.mvilogin.ui.navigation.AppNavHost
import dagger.hilt.android.AndroidEntryPoint

/** @AndroidEntryPoint lets Hilt inject here and makes hiltViewModel() work in every destination. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                AppNavHost()
            }
        }
    }
}
