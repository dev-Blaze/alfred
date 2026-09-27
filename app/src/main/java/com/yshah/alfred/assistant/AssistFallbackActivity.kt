package com.yshah.alfred.assistant

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import com.yshah.alfred.MainActivity

/**
 * Exists only so this app carries an ACTION_ASSIST intent filter, which some OEM assistant
 * pickers use as a qualification signal for RoleManager.ROLE_ASSISTANT alongside the real
 * VoiceInteractionService registration. Forward OEM ACTION_ASSIST launches to the primary UI.
 */
class AssistFallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
