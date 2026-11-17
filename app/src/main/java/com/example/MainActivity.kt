package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.example.data.ai.configureAppCheck
import com.example.ui.WibesAppRoot
import com.example.ui.viewmodel.CompilerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch Intent before any UI or ViewModel creation
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        val viewModel = ViewModelProvider(
            this,
            CompilerViewModel.Factory(application)
        )[CompilerViewModel::class.java]

        setContent {
            WibesAppRoot(viewModel = viewModel)
        }
    }
}
