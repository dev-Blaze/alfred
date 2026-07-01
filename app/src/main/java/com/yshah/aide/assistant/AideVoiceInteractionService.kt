package com.yshah.aide.assistant

import android.service.voice.VoiceInteractionService

/**
 * Registers Aide as the device's VoiceInteractionService (the RoleManager.ROLE_ASSISTANT
 * backend). All actual behavior is manifest-driven (see res/xml/aide_voice_interaction_service.xml)
 * and delegated to AideSessionService/AideVoiceInteractionSession per invocation — this class
 * itself needs no overrides.
 */
class AideVoiceInteractionService : VoiceInteractionService()
