package com.yshah.alfred.convo

/** Preserve the original in History; URLs and Markdown are presentation, not speech. */
internal fun spokenReply(text: String): String = text
    .replace(Regex("\\[([^]]+)]\\(https?://[^)]+\\)"), "$1")
    .replace(Regex("https?://\\S+"), "")
    .replace(Regex("(?m)^\\s{0,3}(?:#{1,6}\\s+|[-*]\\s+)"), "")
    .replace("**", "")
    .replace("`", "")
    .replace(Regex("\\s+"), " ")
    .trim()
