package com.yshah.aide.assistant

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yshah.aide.R
import com.yshah.aide.ui.overlay.AideOverlayContent
import com.yshah.aide.ui.theme.AideTheme

/**
 * Retained infrastructure for the AOSP assist-invocation path (see the plan's "Samsung side
 * button" finding — on this device/OS, the side button launches MainActivity directly instead of
 * invoking this session, so MainActivity is the primary overlay UI now). Kept because the
 * registration is valid and may still be exercised via other invocation paths (e.g.
 * ACTION_VOICE_COMMAND from another app). Hosted by the framework as a session window (not an
 * Activity), so lifecycle/saved-state/view-model tree owners are implemented manually.
 */
class AideVoiceInteractionSession(private val appContext: Context) :
    VoiceInteractionSession(appContext),
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var isUnlockedState by mutableStateOf(false)

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            isUnlockedState = true
        }
    }
    private var receiverRegistered = false

    init {
        setTheme(R.style.Theme_Aide_Overlay)
    }

    override fun onCreateContentView(): View {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val keyguardManager = appContext.getSystemService(KeyguardManager::class.java)
        isUnlockedState = keyguardManager?.isKeyguardLocked != true

        return ComposeView(appContext).apply {
            setViewTreeLifecycleOwner(this@AideVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@AideVoiceInteractionSession)
            setViewTreeViewModelStoreOwner(this@AideVoiceInteractionSession)
            setContent {
                AideTheme {
                    AideOverlayContent(isUnlocked = isUnlockedState, onScrimClick = { hide() })
                }
            }
        }
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        if (!isUnlockedState && !receiverRegistered) {
            appContext.registerReceiver(
                userPresentReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                Context.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onHide() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        unregisterUserPresentReceiver()
        super.onHide()
    }

    override fun onDestroy() {
        unregisterUserPresentReceiver()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        hide()
    }

    private fun unregisterUserPresentReceiver() {
        if (receiverRegistered) {
            appContext.unregisterReceiver(userPresentReceiver)
            receiverRegistered = false
        }
    }
}
