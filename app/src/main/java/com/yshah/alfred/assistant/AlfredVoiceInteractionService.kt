package com.yshah.alfred.assistant

import android.service.voice.VoiceInteractionService

/**
 * Registers Alfred as the device's VoiceInteractionService (the RoleManager.ROLE_ASSISTANT
 * backend). All actual behavior is manifest-driven (see res/xml/alfred_voice_interaction_service.xml)
 * and delegated to AlfredSessionService/AlfredVoiceInteractionSession per invocation — this class
 * itself needs no overrides.
 */
class AlfredVoiceInteractionService : VoiceInteractionService()
