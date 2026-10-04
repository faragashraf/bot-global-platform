package com.botglobal.lamma.app.state

interface RecentGameSessionPreferences {
    fun restore(): String?
    fun save(sessionId: String)
    fun clear()
}

object UnavailableRecentGameSessionPreferences : RecentGameSessionPreferences {
    override fun restore(): String? = null
    override fun save(sessionId: String) = Unit
    override fun clear() = Unit
}
