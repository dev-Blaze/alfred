package com.yshah.aide

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yshah.aide.settings.SettingsActivity
import com.yshah.aide.ui.overlay.AideOverlayScreen
import com.yshah.aide.ui.theme.AideTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The actual invocation target for Samsung's side-button "Digital assistant app" gesture (see the
 * plan's "Samsung side button" finding — it launches the assistant package's launcher Activity
 * directly, not AideVoiceInteractionSession). Styled as a translucent bottom sheet and finishes
 * quickly on dismiss so it reads as an overlay rather than a full app switch. A plain Activity
 * can't show over the keyguard by default, which is what gives us "must unlock first" for free.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AideTheme {
                AideOverlayScreen(
                    onDismiss = { finish() },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                )
            }
        }
    }

    /**
     * Leaving the foreground for ANY reason (back gesture, home button, task switch — not just
     * the explicit scrim-tap dismiss) must tear this down, not just background it. singleTask's
     * default back behavior can move the task to back instead of finishing it, which would leave
     * the ViewModel (and its SpeechCaptureController/ConvoStateMachine singletons) alive with a
     * stale, possibly-still-listening session that then conflicts with the next invocation —
     * confirmed on-device as the cause of spurious "didn't catch that" errors after backgrounding
     * mid-capture. finish() on an already-finishing activity (the explicit-dismiss path) is a
     * harmless no-op.
     */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            finish()
        }
    }
}
