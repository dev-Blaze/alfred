package com.yshah.aide.history

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yshah.aide.ui.theme.AideTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AideTheme {
                HistoryScreen()
            }
        }
    }
}
