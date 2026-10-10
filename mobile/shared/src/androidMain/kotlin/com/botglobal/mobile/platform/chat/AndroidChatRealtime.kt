package com.botglobal.mobile.platform.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The consumer supplies its native SignalR transport; retry/lifetime belongs to the SDK. */
interface ChatRealtimeTransport {
    val connected: Boolean
    suspend fun start(onHint: (ChatMessageHint) -> Unit)
    suspend fun stop()
}

class AndroidChatRealtime(private val transport: suspend () -> ChatRealtimeTransport?) : ChatRealtime {
    private val lock = Mutex()
    private var lifetime: CoroutineScope? = null
    private var active: ChatRealtimeTransport? = null
    override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit) = connect(onHint) { }
    override suspend fun connect(onHint: suspend (ChatMessageHint) -> Unit, onConnected: suspend () -> Unit) {
        lock.withLock {
            if (lifetime != null) return
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            lifetime = scope
            // Factory captures a single credential before starting the reconnect loop.
            val captured = try { transport() } catch (error: Exception) { lifetime = null; scope.cancel(); throw error }
                ?: run { lifetime = null; scope.cancel(); return }
            active = captured
            scope.launch {
                var backoff = 1_000L
                var wasConnected = false
                while (isActive) {
                    if (!captured.connected) {
                        wasConnected = false
                        try { captured.start { hint -> scope.launch {
                            try { onHint(hint) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { }
                        } } }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { }
                    }
                    if (captured.connected) {
                        if (!wasConnected) {
                            try { onConnected(); wasConnected = true }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { }
                        }
                        backoff = 1_000
                    } else backoff = (backoff * 2).coerceAtMost(30_000)
                    delay(backoff)
                }
            }
        }
    }
    override suspend fun disconnect() {
        lock.withLock {
            lifetime?.cancel(); lifetime = null
            val previous = active; active = null
            try { previous?.stop() } catch (_: Exception) { }
        }
    }
}
