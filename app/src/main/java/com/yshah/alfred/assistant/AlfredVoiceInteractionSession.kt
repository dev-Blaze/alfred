package com.yshah.alfred.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import com.yshah.alfred.MainActivity

/**
 * Thin launcher for the assist-invocation path. Depending on how the assistant is configured,
 * Samsung's One UI side-button gesture either launches [MainActivity] directly or invokes this
 * session (see the plan's "Samsung side button" finding) — so rather than host a second, stubbed
 * copy of the overlay UI here (which also can't easily create the Hilt-injected ViewModel the real
 * overlay needs), the session simply launches [MainActivity], the single real overlay, and
 * finishes. That keeps both invocation paths pixel-identical.
 *
 * Lock-gating still holds: MainActivity is a plain Activity with no showWhenLocked flag, so the
 * system won't surface it over the keyguard — the device must be unlocked first.
 */
class AlfredVoiceInteractionSession(private val appContext: Context) :
    VoiceInteractionSession(appContext) {

    // The framework requires a content view; an empty one keeps the session window invisible
    // while MainActivity comes up over the current app.
    override fun onCreateContentView(): View = View(appContext)

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // startAssistantActivity is the assist-context-sanctioned launch path (avoids
        // background-activity-start restrictions that a bare startActivity would hit here).
        startAssistantActivity(intent)
        finish()
    }
}
