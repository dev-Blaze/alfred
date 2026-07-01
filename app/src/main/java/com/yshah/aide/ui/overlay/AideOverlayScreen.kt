package com.yshah.aide.ui.overlay

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun AideOverlayScreen(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: AideOverlayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.shouldDismiss) {
        if (uiState.shouldDismiss) onDismiss()
    }

    fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Tracked independently — a user who granted mic access before notifications existed in the
    // app must still be prompted for POST_NOTIFICATIONS on their next capture, not skipped just
    // because RECORD_AUDIO alone was already granted.
    var hasRecordAudio by remember { mutableStateOf(isGranted(Manifest.permission.RECORD_AUDIO)) }
    var hasNotifications by remember { mutableStateOf(isGranted(Manifest.permission.POST_NOTIFICATIONS)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results[Manifest.permission.RECORD_AUDIO]?.let { hasRecordAudio = it }
        results[Manifest.permission.POST_NOTIFICATIONS]?.let { hasNotifications = it }
        if (hasRecordAudio) {
            viewModel.onMicTapped()
        } else {
            // A denied permission won't show the system dialog again — surface a way forward
            // instead of the mic tap silently doing nothing.
            viewModel.onCapturePermissionDenied()
        }
    }

    AideModeOverlayContent(
        activeMode = uiState.activeMode,
        captureState = uiState.captureState,
        convoState = uiState.convoState,
        onModeSelected = viewModel::onModeSelected,
        onScrimClick = onDismiss,
        onOpenSettings = onOpenSettings,
        onMicTapped = {
            if (hasRecordAudio && hasNotifications) {
                viewModel.onMicTapped()
            } else {
                val missing = buildList {
                    if (!hasRecordAudio) add(Manifest.permission.RECORD_AUDIO)
                    if (!hasNotifications) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(missing.toTypedArray())
            }
        },
        onEndConversation = viewModel::onEndConversation,
    )
}
