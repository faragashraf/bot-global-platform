package com.botglobal.mobile.platform.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val ChatDiskLock = Any()
private const val ChatVoiceLogTag = "ChatVoiceRecorder"

class AndroidChatDurableStore(context: Context) : ChatDurableStore {
    private val root = durableDirectory(File(context.noBackupFilesDir, "platform-chat/state"))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    override suspend fun rememberedScope(identityKey: String): ChatAccountScope? = synchronized(ChatDiskLock) {
        val file = File(root, "binding-${identityKey.toByteArray().sha256()}.json")
        if (file.exists()) json.decodeFromString<ChatAccountScope>(file.readText()) else null
    }
    override suspend fun rememberScope(identityKey: String, scope: ChatAccountScope) = synchronized(ChatDiskLock) {
        atomicWrite(File(root, "binding-${identityKey.toByteArray().sha256()}.json"), json.encodeToString(scope).toByteArray())
    }
    override suspend fun load(scope: ChatAccountScope): ChatDurableState = synchronized(ChatDiskLock) {
        val file = stateFile(scope)
        if (!file.exists()) ChatDurableState() else json.decodeFromString(file.readText())
    }
    override suspend fun save(scope: ChatAccountScope, state: ChatDurableState) = synchronized(ChatDiskLock) { atomicWrite(stateFile(scope), json.encodeToString(state).toByteArray()) }
    override suspend fun deleteAccount(scope: ChatAccountScope) { synchronized(ChatDiskLock) {
        check(accountDir(scope).deleteRecursively())
        root.listFiles()?.filter { it.name.startsWith("binding-") }?.forEach { file ->
            if (json.decodeFromString<ChatAccountScope>(file.readText()) == scope) check(file.delete())
        }
    } }
    private fun stateFile(scope: ChatAccountScope) = File(accountDir(scope), "chat-state.json")
    private fun accountDir(scope: ChatAccountScope) = durableDirectory(File(root, scope.safeKey()))
}

class AndroidChatVoiceStore(context: Context) : ChatVoiceStore {
    private val root = durableDirectory(File(context.noBackupFilesDir, "platform-chat/voice"))
    private val drafts = durableDirectory(File(context.noBackupFilesDir, "platform-chat/drafts"))
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    override suspend fun readDraft(draft: ChatVoiceDraft): ByteArray? = synchronized(ChatDiskLock) {
        safeDraft(draft)?.takeIf { it.length() == draft.length && draft.length in 1..MaxVoiceBytes }?.readBytes()
            ?.takeIf { draft.sha256 == null || it.sha256() == draft.sha256 }
    }
    override suspend fun inspectDraft(draft: ChatVoiceDraft): ChatDraftRead = withContext(Dispatchers.IO) {
        try { readDraft(draft)?.let { ChatDraftRead.Available(it, it.sha256()) } ?: ChatDraftRead.MissingOrCorrupt }
        catch (_: java.io.IOException) { ChatDraftRead.TemporaryFailure }
    }
    override suspend fun promoteDraft(scope: ChatAccountScope, transferId: String, expectedSha256: String,
        expectedLength: Long, draft: ChatVoiceDraft): StoredChatVoice? {
        if (draft.owner != null && draft.owner != scope) return null
        val bytes = readDraft(draft) ?: return null
        // Keep the draft until the controller has committed message + local key + outbox removal.
        return storeVerified(scope, transferId, expectedSha256, expectedLength, bytes)
    }
    override suspend fun deleteDraft(draft: ChatVoiceDraft) { synchronized(ChatDiskLock) { safeDraft(draft)?.delete() } }
    override suspend fun storeVerified(scope: ChatAccountScope, transferId: String, expectedSha256: String,
        expectedLength: Long, content: ByteArray): StoredChatVoice? = synchronized(ChatDiskLock) {
        if (content.size.toLong() != expectedLength || expectedLength !in 1..MaxVoiceBytes) return null
        val actual = content.sha256()
        if (!actual.equals(expectedSha256, ignoreCase = false)) return null
        val directory = accountDir(scope)
        val localKey = safeOpaque(transferId) + ".m4a"
        val file = File(directory, localKey)
        return try {
            if (!file.exists() || file.length() != expectedLength || file.readBytes().sha256() != actual) atomicWrite(file, content)
            val indexFile = File(directory, "voice-index.json")
            val index = if (indexFile.exists()) json.decodeFromString<Map<String, VoiceIndex>>(indexFile.readText()) else emptyMap()
            atomicWrite(indexFile, json.encodeToString(index + (transferId to VoiceIndex(localKey, expectedLength, actual))).toByteArray())
            StoredChatVoice(localKey, expectedLength, actual)
        } catch (_: Exception) { null } // A failed index writer must never remove published bytes.
    }
    override suspend fun read(scope: ChatAccountScope, localKey: String): ByteArray? = synchronized(ChatDiskLock) {
        val indexFile = File(accountDir(scope), "voice-index.json")
        if (!indexFile.exists()) return null
        val entry = json.decodeFromString<Map<String, VoiceIndex>>(indexFile.readText()).values.firstOrNull { it.localKey == localKey } ?: return null
        fileFor(scope, localKey)?.readBytes()?.takeIf { it.size.toLong() == entry.length && it.sha256() == entry.sha256 }
    }
    override suspend fun deleteAccount(scope: ChatAccountScope) { synchronized(ChatDiskLock) { accountDir(scope).deleteRecursively(); File(drafts, scope.safeKey()).deleteRecursively() } }
    internal fun draftFile(scope: ChatAccountScope, conversationId: String): File = File(draftDir(scope, conversationId), UUID.randomUUID().toString() + ".m4a")
    internal fun draftFor(draft: ChatVoiceDraft): File? = safeDraft(draft)
    internal fun fileFor(scope: ChatAccountScope, localKey: String): File? = runCatching {
        val safe = safeOpaque(localKey.removeSuffix(".m4a")) + ".m4a"
        File(accountDir(scope), safe).takeIf { it.exists() }
    }.getOrNull()
    private fun draftDir(scope: ChatAccountScope, conversationId: String) = durableDirectory(File(File(drafts, scope.safeKey()), conversationId.toByteArray().sha256()))
    private fun safeDraft(draft: ChatVoiceDraft): File? = runCatching {
        val directory = if (draft.owner != null && draft.conversationId != null) draftDir(draft.owner, draft.conversationId) else drafts
        File(directory, safeOpaque(draft.token.removeSuffix(".m4a")) + ".m4a").takeIf { it.exists() }
    }.getOrNull()
    private fun accountDir(scope: ChatAccountScope) = durableDirectory(File(root, scope.safeKey()))
    @kotlinx.serialization.Serializable private data class VoiceIndex(val localKey: String, val length: Long, val sha256: String)
    private companion object { const val MaxVoiceBytes = 10L * 1024 * 1024 }
}

fun interface ChatActiveCallGuard { fun isCallActive(): Boolean }
fun interface ChatMicrophonePermission { fun isGranted(): Boolean }

class AndroidChatVoiceRecorder(
    private val context: Context,
    private val store: AndroidChatVoiceStore,
    private val calls: ChatActiveCallGuard,
    private val permission: ChatMicrophonePermission,
) : ChatVoiceRecorder {
    override val available = true
    private val mutableProgress = MutableStateFlow(ChatRecordingProgress())
    override val progress = mutableProgress.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var focus: Any? = null
    private val mediaScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: ChatAccountScope? = null
    private var conversationId: String? = null
    private var automaticStop: (ChatVoiceDraft?) -> Unit = {}
    override fun onAutomaticStop(callback: (ChatVoiceDraft?) -> Unit) { automaticStop = callback }
    private val legacyFocus = AudioManager.OnAudioFocusChangeListener { if (it < 0) mediaScope.launch { cancel(); automaticStop(null) } }
    private var amplitudeJob: Job? = null
    private val amplitudes = mutableListOf<Float>()
    override suspend fun start(): Boolean = false
    override suspend fun start(scope: ChatAccountScope, conversationId: String): Boolean = withContext(Dispatchers.Main.immediate) {
        if (recorder != null || calls.isCallActive() || !permission.isGranted()) return@withContext false
        val audio = context.getSystemService(AudioManager::class.java)
        if (!requestFocus(audio, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, AudioAttributes.USAGE_VOICE_COMMUNICATION)) return@withContext false
        owner = scope; this@AndroidChatVoiceRecorder.conversationId = conversationId
        val target = store.draftFile(scope, conversationId)
        try {
            @Suppress("DEPRECATION")
            val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            recorder = created
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(64_000)
            created.setMaxDuration(300_000)
            created.setMaxFileSize(10L * 1024 * 1024)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                    mediaScope.launch { automaticStop(stop()) }
            }
            created.setOutputFile(target.absolutePath)
            created.prepare(); created.start()
            recorder = created; file = target; startedAt = SystemClock.elapsedRealtime(); amplitudes.clear()
            mutableProgress.value = ChatRecordingProgress()
            amplitudeJob = mediaScope.launch {
                while (isActive) {
                    delay(120)
                    val amplitude = runCatching { created.maxAmplitude }.getOrDefault(0)
                    amplitudes += (amplitude / 32767f).coerceIn(0f, 1f)
                    mutableProgress.value = ChatRecordingProgress((SystemClock.elapsedRealtime() - startedAt).coerceIn(0, 300_000).toInt(), amplitudes.last())
                }
            }
            true
        } catch (_: Exception) { runCatching { recorder?.release() }; recorder = null; file = null; target.delete(); abandonFocus(); false }
    }
    override suspend fun stop(): ChatVoiceDraft? = withContext(Dispatchers.Main.immediate) {
        val active = recorder ?: return@withContext null
        val target = file
        val elapsedMilliseconds = (SystemClock.elapsedRealtime() - startedAt).coerceIn(1L, 300_000L).toInt()
        val amplitudeSnapshot = amplitudes.toList()
        try {
            amplitudeJob?.cancel(); amplitudeJob = null
            active.stop()
            runCatching { active.release() }.onFailure { logRecorderFailure("release", it) }
            recorder = null; file = null; abandonFocus()
            if (target == null || !target.exists() || target.length() !in 1..(10L * 1024 * 1024)) { target?.delete(); null }
            else {
                val measured = withContext(Dispatchers.IO) {
                    val bytes = target.readBytes()
                    syncDraftBestEffort(target)
                    val duration = ChatVoiceDuration.milliseconds(bytes) ?: elapsedMilliseconds.also {
                        Log.w(ChatVoiceLogTag, "voice recorder duration fallback")
                    }
                    duration to bytes.sha256()
                }
                ChatVoiceDraft(target.name, measured.first, target.length(), amplitudeSnapshot, owner, conversationId, measured.second)
            }
        } catch (error: Exception) {
            logRecorderFailure("finalize", error)
            amplitudeJob?.cancel(); amplitudeJob = null; runCatching { active.release() }; recorder = null; file = null; target?.delete(); abandonFocus(); null
        }
    }
    override suspend fun cancel() = withContext(Dispatchers.Main.immediate) { amplitudeJob?.cancel(); amplitudeJob = null; runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null; file?.delete(); file = null; abandonFocus() }
    override suspend fun discard(draft: ChatVoiceDraft) { store.deleteDraft(draft) }
    private fun requestFocus(audio: AudioManager, gain: Int, usage: Int): Boolean {
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(gain).setOnAudioFocusChangeListener(legacyFocus).setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focus = request; audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(legacyFocus, AudioManager.STREAM_MUSIC, gain)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonFocus() {
        val audio = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) (focus as? AudioFocusRequest)?.let(audio::abandonAudioFocusRequest)
        else { @Suppress("DEPRECATION") audio.abandonAudioFocus(legacyFocus) }
        focus = null
    }
}

private fun logRecorderFailure(stage: String, error: Throwable) {
    Log.w(ChatVoiceLogTag, "voice recorder $stage failed type=${error.javaClass.simpleName}")
}

class AndroidChatVoicePlayer(
    private val context: Context,
    private val store: AndroidChatVoiceStore,
    private val calls: ChatActiveCallGuard,
) : ChatVoicePlayer {
    override val available = true
    private val mutableState = MutableStateFlow(ChatPlayback())
    override val state = mutableState.asStateFlow()
    private val mediaScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null
    private var preparationJob: Job? = null
    private var generation = 0L
    private var player: MediaPlayer? = null
    private var focus: Any? = null
    private val legacyFocus = AudioManager.OnAudioFocusChangeListener { if (it < 0) mediaScope.launch { stop() } }
    override suspend fun play(scope: ChatAccountScope, localKey: String): Boolean {
        val ticket = withContext(Dispatchers.Main.immediate) { releasePlayer(); ++generation }
        val valid = withContext(Dispatchers.IO) { runCatching { store.read(scope, localKey) != null }.getOrDefault(false) }
        return withContext(Dispatchers.Main.immediate) {
            if (ticket != generation) false
            else if (!valid) { fail(localKey); false }
            else store.fileFor(scope, localKey)?.let { playFile(it, localKey) } ?: false
        }
    }
    override suspend fun playDraft(draft: ChatVoiceDraft): Boolean {
        val ticket = withContext(Dispatchers.Main.immediate) { releasePlayer(); ++generation }
        val valid = store.inspectDraft(draft) is ChatDraftRead.Available
        return withContext(Dispatchers.Main.immediate) {
            if (ticket != generation) false
            else if (!valid) { fail(draft.token); false }
            else store.draftFor(draft)?.let { playFile(it, draft.token) } ?: false
        }
    }
    private fun playFile(file: File, key: String): Boolean {
        releasePlayer()
        if (calls.isCallActive()) return false
        val audio = context.getSystemService(AudioManager::class.java)
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(legacyFocus)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focus = request; audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(legacyFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail(key); return false }
        return try {
            val created = MediaPlayer()
            player = created
            mutableState.value = ChatPlayback(key, ChatPlaybackPhase.Preparing)
            created.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            created.setDataSource(file.absolutePath)
            created.setOnPreparedListener {
                if (player !== it) return@setOnPreparedListener
                preparationJob?.cancel(); preparationJob = null
                if (calls.isCallActive()) { releasePlayer(); return@setOnPreparedListener }
                if (runCatching { it.start() }.isFailure) { fail(key); return@setOnPreparedListener }
                mutableState.value = ChatPlayback(key, ChatPlaybackPhase.Playing, 0, it.duration)
                watchProgress(it)
            }
            created.setOnCompletionListener {
                if (player === it) {
                    val completed = mutableState.value.copy(phase = ChatPlaybackPhase.Completed, elapsedMilliseconds = mutableState.value.durationMilliseconds)
                    releasePlayer(); mutableState.value = completed
                }
            }
            created.setOnErrorListener { value, _, _ -> if (player === value) fail(key); true }
            created.prepareAsync()
            preparationJob = mediaScope.launch {
                delay(5_000)
                if (player === created && mutableState.value.phase == ChatPlaybackPhase.Preparing) fail(key)
            }
            true
        } catch (_: Exception) { fail(key); false }
    }
    private fun watchProgress(active: MediaPlayer) {
        progressJob?.cancel()
        progressJob = mediaScope.launch {
            while (player === active && isActive) {
                if (mutableState.value.phase == ChatPlaybackPhase.Playing)
                    mutableState.value = mutableState.value.copy(elapsedMilliseconds = runCatching { active.currentPosition }.getOrDefault(0))
                delay(200)
            }
        }
    }
    override suspend fun pause() = withContext(Dispatchers.Main.immediate) {
        if (mutableState.value.phase == ChatPlaybackPhase.Playing) {
            player?.pause(); mutableState.value = mutableState.value.copy(phase = ChatPlaybackPhase.Paused)
        }
    }
    override suspend fun resume(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (calls.isCallActive() || mutableState.value.phase != ChatPlaybackPhase.Paused || player == null) false
        else { player?.start(); mutableState.value = mutableState.value.copy(phase = ChatPlaybackPhase.Playing); true }
    }
    override suspend fun stop() = withContext(Dispatchers.Main.immediate) { generation++; releasePlayer() }
    private fun fail(key: String) { releasePlayer(); mutableState.value = ChatPlayback(key, ChatPlaybackPhase.Failed) }
    private fun releasePlayer() {
        progressJob?.cancel(); progressJob = null
        preparationJob?.cancel(); preparationJob = null
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null; abandonFocus()
        mutableState.value = ChatPlayback()
    }
    private fun abandonFocus() {
        val audio = context.getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) (focus as? AudioFocusRequest)?.let(audio::abandonAudioFocusRequest)
        else { @Suppress("DEPRECATION") audio.abandonAudioFocus(legacyFocus) }
        focus = null
    }
}

private fun ChatAccountScope.safeKey() = (applicationId + "\n" + subjectId).toByteArray().sha256()
private fun ByteArray.sha256(): String {
    val digits = "0123456789abcdef"
    return MessageDigest.getInstance("SHA-256").digest(this).joinToString("") {
        val value = it.toInt() and 0xff
        "${digits[value ushr 4]}${digits[value and 0x0f]}"
    }
}
private fun safeOpaque(value: String): String { require(value.matches(Regex("[A-Za-z0-9-]{1,100}"))); return value }
private fun atomicWrite(target: File, bytes: ByteArray) {
    target.parentFile?.let(::durableDirectory)
    val temporary = File(target.parentFile, target.name + "." + UUID.randomUUID() + ".partial")
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Os.rename(temporary.absolutePath, target.absolutePath)
        syncDirectory(target.parentFile!!)
    } catch (error: Exception) { temporary.delete(); throw error }
}
private fun syncDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}
private fun syncDraftBestEffort(target: File) {
    runCatching { FileOutputStream(target, true).use { it.fd.sync() } }
        .onFailure { logRecorderFailure("file-sync", it) }
    runCatching { syncDirectory(target.parentFile!!) }
        .onFailure { logRecorderFailure("directory-sync", it) }
}
private fun durableDirectory(directory: File): File {
    if (!directory.isDirectory) {
        directory.parentFile?.let(::durableDirectory)
        if (!directory.mkdir() && !directory.isDirectory) throw java.io.IOException("chat directory unavailable")
        directory.parentFile?.let(::syncDirectory)
    }
    return directory
}
