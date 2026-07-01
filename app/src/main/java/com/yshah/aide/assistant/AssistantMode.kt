package com.yshah.aide.assistant

// CAMERA mode was removed for now — see the plan's "Camera mode deferred" note for why and
// what's needed before re-adding it.
enum class AssistantMode(val key: String) {
    TASK("task"),
    NOTE("note"),
    CONVO("convo");

    companion object {
        fun fromKey(key: String?): AssistantMode? = entries.find { it.key == key }
    }
}
