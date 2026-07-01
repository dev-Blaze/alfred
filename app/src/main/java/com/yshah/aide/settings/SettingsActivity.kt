package com.yshah.aide.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yshah.aide.ui.theme.AideTheme
import dagger.hilt.android.AndroidEntryPoint

/** Reached from a gear icon inside the overlay content (wired up once that affordance exists). */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AideTheme {
                SettingsScreen()
            }
        }
    }
}
