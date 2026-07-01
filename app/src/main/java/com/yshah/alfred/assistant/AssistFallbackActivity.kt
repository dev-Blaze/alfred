package com.yshah.alfred.assistant

import android.app.Activity
import android.os.Bundle

/**
 * Exists only so this app carries an ACTION_ASSIST intent filter, which some OEM assistant
 * pickers use as a qualification signal for RoleManager.ROLE_ASSISTANT alongside the real
 * VoiceInteractionService registration. The actual assist UI is the VoiceInteractionSession
 * overlay (AlfredVoiceInteractionSession) — this Activity should never be user-visible.
 */
class AssistFallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
