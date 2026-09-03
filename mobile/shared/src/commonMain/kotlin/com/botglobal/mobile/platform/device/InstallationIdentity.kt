package com.botglobal.mobile.platform.device

import com.botglobal.mobile.platform.preferences.PreferenceStore
import kotlin.jvm.JvmInline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@JvmInline
value class InstallationId(val value: String) {
    init {
        require(value.isNotBlank()) { "An installation identifier is required." }
    }

    override fun toString(): String = "<redacted>"
}

interface InstallationIdStore {
    fun read(): InstallationId?

    fun write(value: InstallationId)
}

fun interface InstallationIdGenerator {
    fun generate(): InstallationId
}

class InstallationIdentity(
    private val store: InstallationIdStore,
    private val generator: InstallationIdGenerator,
) {
    private val mutex = Mutex()

    suspend fun getOrCreate(): InstallationId = mutex.withLock {
        store.read() ?: generator.generate().also(store::write)
    }
}

class PreferenceInstallationIdStore(
    private val preferences: PreferenceStore,
    private val key: String,
) : InstallationIdStore {
    override fun read(): InstallationId? =
        preferences.string(key)?.trim()?.takeIf(String::isNotEmpty)?.let(::InstallationId)

    override fun write(value: InstallationId) {
        preferences.putString(key, value.value)
    }
}
