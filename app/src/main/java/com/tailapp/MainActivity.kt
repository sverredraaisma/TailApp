package com.tailapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.tailapp.navigation.TailAppNavHost
import com.tailapp.ui.theme.TailAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as TailApp).container

        setContent {
            TailAppTheme {
                val navController = rememberNavController()
                TailAppNavHost(navController = navController, container = container)
            }
        }
    }
}
