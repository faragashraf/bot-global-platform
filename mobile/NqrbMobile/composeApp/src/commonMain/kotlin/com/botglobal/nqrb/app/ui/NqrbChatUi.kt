package com.botglobal.nqrb.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.botglobal.mobile.platform.chat.ChatLoadState
import com.botglobal.mobile.platform.chat.ChatMessage
import com.botglobal.mobile.platform.chat.ChatSnapshot
import com.botglobal.mobile.platform.chat.timeline
import com.botglobal.mobile.platform.chat.ChatVoiceDraft
import com.botglobal.nqrb.app.state.NqrbAppState
import com.botglobal.nqrb.app.state.NqrbChatCallTarget
import com.botglobal.nqrb.app.state.NqrbChatCallUnavailableReason
import com.botglobal.nqrb.app.state.NqrbChatRecordingState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import com.botglobal.mobile.platform.chat.ChatPlayback
import com.botglobal.mobile.platform.chat.ChatPlaybackPhase
import com.botglobal.mobile.platform.chat.PendingChatText
import com.botglobal.mobile.platform.chat.PendingChatVoice
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.botglobal.mobile.platform.chat.ChatTextFailure
import com.botglobal.mobile.platform.chat.ChatVoiceFailure
import com.botglobal.mobile.platform.chat.ChatVoiceLoadState

internal data class NqrbChatStrings(
    val chats: String,
    val empty: String,
    val emptyBody: String,
    val retry: String,
    val back: String,
    val messageHint: String,
    val send: String,
    val record: String,
    val stop: String,
    val play: String,
    val cancel: String,
    val sendVoice: String,
    val recording: String,
    val sent: String,
    val delivered: String,
    val read: String,
    val retrying: String,
    val localUnavailable: String,
    val permissionTitle: String,
    val permissionBody: String,
    val continueLabel: String,
    val storageTitle: String,
    val storageBody: String,
    val temporaryBody: String,
    val unavailable: String,
    val participantUnavailable: String,
    val autoStopped: String,
    val operationFailed: String,
    val arabic: Boolean = false,
) {
    fun localized(ar: String, en: String) = if (arabic) ar else en
    val voiceNote get() = localized("رسالة صوتية", "Voice note")
    val pause get() = localized("إيقاف مؤقت", "Pause")
    val playbackStop get() = localized("إيقاف الاستماع", "Stop playback")
    val preparing get() = localized("جارٍ التجهيز", "Preparing")
    val playing get() = localized("جارٍ الاستماع", "Playing")
    val paused get() = localized("الاستماع متوقف مؤقتًا", "Paused")
    val completed get() = localized("اكتمل الاستماع", "Playback completed")
    val previewReady get() = localized("التسجيل جاهز للمراجعة", "Preview ready")
    val queued get() = localized("محفوظ للإرسال", "Queued for delivery")
    val notSent get() = localized("لم تُرسل", "Not sent")
    val finalizing get() = localized("جارٍ حفظ التسجيل", "Saving recording")
    val newMessages get() = localized("رسائل جديدة", "New messages")
    val recordAgain get() = localized("سجّل من جديد", "Record again")
    val remove get() = localized("إزالة", "Remove")
    val edit get() = localized("تعديل", "Edit")
    val copy get() = localized("نسخ", "Copy")
    val checkDelivery get() = localized("تحقق من الإرسال", "Check delivery")
    val voiceRejected get() = localized("تعذّر إرسال التسجيل. نسختك محفوظة ويمكنك الاستماع إليها.", "The recording was rejected. Your copy is saved and can still be played.")
    fun textFailure(reason: ChatTextFailure) = when (reason) {
        ChatTextFailure.Forbidden -> localized("تعذّر الإرسال لهذا الشخص. رسالتك محفوظة؛ يمكنك تعديلها أو نسخها أو إزالتها.", "Sending to this person was rejected. Your text is saved; edit, copy or remove it.")
        ChatTextFailure.Conflict -> localized("لم نتأكد من إرسال الرسالة. النص محفوظ؛ تحقق من الإرسال قبل إرسال نسخة أخرى.", "Delivery could not be confirmed. Your text is saved; check delivery before sending another copy.")
    }
    val settings get() = localized("فتح الإعدادات", "Open settings")
    val unread get() = localized("غير مقروءة", "Unread")
    fun unreadCount(count: Int) = if (count > 99) localized("أكثر من ٩٩ غير مقروءة", "99+ unread")
        else localized("${digits(count.toString())} غير مقروءة", "$count unread")
    val limit get() = localized("الحد الأقصى ٥ دقائق", "5 minute maximum")
    val localOnly get() = localized("المحفوظ على هذا الجهاز · أعد الاتصال للمزامنة", "Saved on this device · reconnect to sync")
    val openingConversation get() = localized("جارٍ فتح المحادثة الخاصة…", "Opening private conversation…")
    val conversationOpenFailed get() = localized("تعذّر فتح المحادثة. تحقق من الاتصال ثم حاول مرة أخرى.", "Could not open the conversation. Check your connection and try again.")
    val loadingConversation get() = localized("جارٍ تحميل الرسائل", "Loading messages")
    val loadingConversationBody get() = localized("جارٍ فتح هذه المحادثة…", "Opening this conversation…")
    val emptyConversation get() = localized("لا توجد رسائل بعد", "No messages yet")
    val emptyConversationBody get() = localized("اكتب رسالة أدناه لبدء المحادثة.", "Write a message below to start the conversation.")
    val localConversationEmpty get() = localized("لا توجد رسائل محفوظة على هذا الجهاز", "No messages saved on this device")
    val localConversationEmptyBody get() = localized("يظهر هنا فقط ما هو محفوظ على هذا الجهاز.", "Only messages saved on this device can appear here.")
    val messagesUnavailable get() = localized("الرسائل غير متاحة الآن", "Messages are unavailable")
    fun openChatWith(displayName: String) = localized("فتح محادثة مع $displayName", "Open chat with $displayName")
    fun call(displayName: String) = localized("الاتصال بـ $displayName", "Call $displayName")
    fun callUnavailable(displayName: String, reason: NqrbChatCallUnavailableReason?): String = when (reason) {
        NqrbChatCallUnavailableReason.Offline -> localized("لا يمكن الاتصال بـ $displayName لأنه غير متاح حاليًا", "$displayName is currently unavailable for calls")
        NqrbChatCallUnavailableReason.Blocked -> localized("لا يمكن الاتصال بـ $displayName لأنه محظور", "Calling $displayName is unavailable because this person is blocked")
        NqrbChatCallUnavailableReason.Self -> localized("لا يمكنك الاتصال بحسابك", "You cannot call your own account")
        NqrbChatCallUnavailableReason.SignedOut,
        NqrbChatCallUnavailableReason.StaleAccount,
        -> localized("أعد تسجيل الدخول للاتصال بـ $displayName", "Sign in again to call $displayName")
        NqrbChatCallUnavailableReason.Unknown, null -> localized("الاتصال بـ $displayName غير متاح الآن", "Calling $displayName is unavailable right now")
    }
    fun digits(value: String) = if (arabic) value.map { if (it in '0'..'9') "٠١٢٣٤٥٦٧٨٩"[it - '0'] else it }.joinToString("") else value
    fun duration(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return digits("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
    }
    fun accessibleDuration(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return localized("${digits((seconds / 60).toString())} دقيقة و${digits((seconds % 60).toString())} ثانية", "${seconds / 60} minutes ${seconds % 60} seconds")
    }
    fun failure(reason: NqrbChatRecordingState) = when (reason) {
        NqrbChatRecordingState.ActiveCall -> localized("المكالمة نشطة. أنهِ المكالمة قبل التسجيل.", "A call is active. End it before recording.")
        NqrbChatRecordingState.CallInterrupted -> localized("أوقفت المكالمة التسجيل وتم تجاهله. يمكنك التسجيل من جديد.", "The call stopped and discarded this recording. You can record again.")
        NqrbChatRecordingState.BackgroundInterrupted -> localized("توقف التسجيل وتم تجاهله عند مغادرة التطبيق. يمكنك التسجيل من جديد.", "Leaving the app stopped and discarded this recording. You can record again.")
        NqrbChatRecordingState.AudioInterrupted -> localized("أوقف نشاط صوتي آخر التسجيل وتم تجاهله. يمكنك التسجيل من جديد.", "Another audio activity stopped and discarded this recording. You can record again.")
        NqrbChatRecordingState.PermissionDenied -> localized("لم تسمح باستخدام الميكروفون. أعد المحاولة للسماح بالتسجيل.", "Microphone permission was denied. Try again to allow recording.")
        NqrbChatRecordingState.PermissionPermanentlyDenied -> localized("إذن الميكروفون محظور. اسمح به من إعدادات التطبيق.", "Microphone permission is blocked. Allow it in app settings.")
        NqrbChatRecordingState.PlaybackUnavailable -> localized("تعذّر تشغيل هذه النسخة الصوتية.", "This voice copy could not be played.")
        NqrbChatRecordingState.StorageUnavailable -> localized("تعذّر الحفظ على الجهاز. وفر مساحة ثم أعد المحاولة؛ التسجيل غير المرسل محفوظ إن كان متاحًا.", "Could not save on this device. Free space and try again; an available unsent preview is retained.")
        else -> localized("الميكروفون غير متاح الآن. حاول التسجيل مرة أخرى.", "The microphone is unavailable. Try recording again.")
    }
}

internal fun nqrbChatStrings(languageTag: String) = if (languageTag.startsWith("ar")) NqrbChatStrings(
    chats = "المحادثات",
    empty = "ابدأ محادثة من دائرة معارفك",
    emptyBody = "افتح شاشة الأشخاص، ثم اضغط اسم شخص لفتح محادثة خاصة.",
    retry = "إعادة المحاولة",
    back = "رجوع",
    messageHint = "اكتب رسالة",
    send = "إرسال",
    record = "تسجيل رسالة صوتية",
    stop = "إيقاف التسجيل",
    play = "استماع",
    cancel = "إلغاء",
    sendVoice = "إرسال التسجيل",
    recording = "جارٍ التسجيل",
    sent = "تم الإرسال",
    delivered = "تم التوصيل",
    read = "تمت القراءة",
    retrying = "بانتظار إعادة الإرسال",
    localUnavailable = "النسخة المحلية غير متاحة على هذا الجهاز.",
    permissionTitle = "السماح باستخدام الميكروفون",
    permissionBody = "يحتاج نقرب إلى الميكروفون لتسجيل الرسالة الصوتية. لن يبدأ التسجيل أثناء مكالمة نشطة.",
    continueLabel = "متابعة",
    storageTitle = "مكان حفظ التسجيلات",
    storageBody = "التسجيلات الصوتية محفوظة على جهازك. مسح بيانات التطبيق أو حذفه يفقدك نسخك المحفوظة. لا يوجد نسخ احتياطي تلقائي.",
    temporaryBody = "يحتفظ الخادم بنسخة توصيل مؤقتة فقط، وتحذف بعد تأكيد الحفظ على جهاز المستلم أو خلال 7 أيام كحد أقصى.",
    unavailable = "التسجيل الصوتي غير متاح الآن. أنهِ المكالمة الحالية أو فعّل إذن الميكروفون ثم حاول مرة أخرى.",
    participantUnavailable = "هذا الشخص غير متاح حاليًا",
    autoStopped = "توقف التسجيل تلقائيًا عند الوصول إلى الحد المسموح. استمع إليه قبل الإرسال.",
    operationFailed = "تعذّر إتمام العملية. تحقق من الاتصال ومساحة التخزين ثم أعد المحاولة.",
    arabic = true,
) else NqrbChatStrings(
    chats = "Messages",
    empty = "Start from your private circle",
    emptyBody = "Open People, then tap a contact name to open a private chat.",
    retry = "Try again",
    back = "Back",
    messageHint = "Write a message",
    send = "Send",
    record = "Record a voice note",
    stop = "Stop recording",
    play = "Play",
    cancel = "Cancel",
    sendVoice = "Send recording",
    recording = "Recording",
    sent = "Sent",
    delivered = "Delivered",
    read = "Read",
    retrying = "Waiting to retry",
    localUnavailable = "The local copy is unavailable on this device.",
    permissionTitle = "Allow microphone access",
    permissionBody = "Nqrb needs the microphone to record this voice note. Recording never starts over an active call.",
    continueLabel = "Continue",
    storageTitle = "Voice notes stay on this device",
    storageBody = "Voice notes are saved on your device. Clearing app data or uninstalling the app removes your saved copies. There is no automatic backup.",
    temporaryBody = "The server keeps only a temporary delivery copy, deleted after the recipient confirms storage or within seven days at most.",
    unavailable = "Voice recording is unavailable right now. End the active call or enable microphone permission, then try again.",
    participantUnavailable = "This person is currently unavailable",
    autoStopped = "Recording stopped automatically at the limit. Preview it before sending.",
    operationFailed = "Could not complete this action. Check your connection and available storage, then try again.",
)

@Composable
internal fun NqrbChatListScreen(languageTag: String, snapshot: ChatSnapshot, appState: NqrbAppState,
    callTime: (String, String) -> NqrbCallTime = ::basicCallTime) {
    val strings = nqrbChatStrings(languageTag)
    val colors = LocalNqrbColors.current
    val localOnly by appState.chatLocalOnly.collectAsState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = NqrbLayout.ThreadMaxWidth).fillMaxSize().padding(NqrbSpacing.Lg), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Md)) {
        ChatHeader(
            title = strings.chats,
            back = strings.back,
            onBack = { appState.navigation.navigateBack() },
        )
        if (localOnly) {
            Text(strings.localOnly, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            Row {
                TextButton(onClick = appState::refreshChat) { Text(strings.retry) }
                TextButton(onClick = appState::logoutFromLocalChat) { Text(strings.localized("تسجيل الخروج", "Sign out")) }
            }
        }
        if (snapshot.retryableFailure) TextButton(onClick = appState::refreshChat) { Text(strings.retry) }
        when (if (snapshot.conversations.isNotEmpty()) ChatLoadState.Ready else snapshot.state) {
            ChatLoadState.Loading -> Text(strings.retrying, color = colors.textSecondary)
            ChatLoadState.Error -> {
                Text(strings.operationFailed, color = colors.textSecondary)
                TextButton(onClick = appState::refreshChat) { Text(strings.retry) }
            }
            else -> if (snapshot.conversations.isEmpty()) {
                ChatStatePane(strings.empty, strings.emptyBody, Modifier.fillMaxWidth().weight(1f))
            } else LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                items(snapshot.conversationActivity, key = { it.conversation.conversationId }) { activity ->
                    val conversation = activity.conversation
                    val displayName = conversation.counterpartDisplayName ?: strings.participantUnavailable
                    val unreadCount = activity.unreadCount
                    Surface(
                        Modifier.fillMaxWidth().clickable { appState.openChat(conversation.conversationId) },
                        color = colors.surface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Row(Modifier.padding(NqrbSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                                Text(displayName.take(1).uppercase(), color = colors.accent, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(NqrbSpacing.Md))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                val unread = unreadCount > 0
                                val latest = activity.latestMessage
                                val failed = latest == null && (activity.pending?.text?.failure != null || activity.pending?.voice?.failure != null)
                                val recency = if (failed) strings.notSent else if (latest == null && activity.pending != null && activity.pending?.createdAtUtc == null) strings.queued
                                    else chatRecencyLabel(callTime(activity.updatedAtUtc, languageTag))
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text(displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium, color = colors.textPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (unreadCount > 0) {
                                        Spacer(Modifier.width(NqrbSpacing.Sm))
                                        NqrbUnreadBadge(unreadCount, strings.unreadCount(unreadCount))
                                    }
                                    Spacer(Modifier.width(NqrbSpacing.Sm))
                                    Text(recency, Modifier.widthIn(max = 104.dp), style = MaterialTheme.typography.labelSmall,
                                        color = if (failed) colors.destructive else colors.textSecondary, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (latest != null) { if (latest.kind == "voice") strings.voiceNote else latest.text ?: strings.messageHint }
                                        else activity.pending?.let { it.text?.text ?: strings.voiceNote } ?: strings.messageHint,
                                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun NqrbUnreadBadge(count: Int, label: String, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val colors = LocalNqrbColors.current
    Box(
        modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).background(colors.accent, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.background,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

internal fun chatRecencyLabel(time: NqrbCallTime): String =
    if (time.dayLabel == "Today" || time.dayLabel == "اليوم") time.timeLabel else time.dayLabel

@Composable
internal fun NqrbChatThreadScreen(
    languageTag: String,
    snapshot: ChatSnapshot,
    recordingState: NqrbChatRecordingState,
    voiceDraft: ChatVoiceDraft?,
    appState: NqrbAppState,
    callTime: (String, String) -> NqrbCallTime = ::basicCallTime,
) {
    val strings = nqrbChatStrings(languageTag)
    val colors = LocalNqrbColors.current
    val conversation = snapshot.conversations.firstOrNull { it.conversationId == snapshot.selectedConversationId }
    val conversationId = conversation?.conversationId
    val contactBook by appState.contactBook.state.collectAsState()
    val directory by appState.callingDirectory.state.collectAsState()
    val authentication by appState.identity.state.collectAsState()
    val localOnly by appState.chatLocalOnly.collectAsState()
    val displayName = conversation?.counterpartReference?.let { reference ->
        privateContactDisplayName(contactBook, reference, conversation.counterpartDisplayName ?: strings.participantUnavailable)
    } ?: conversation?.counterpartDisplayName ?: strings.participantUnavailable
    val callTarget = remember(
        authentication,
        directory,
        contactBook,
        snapshot.account,
        snapshot.accountGeneration,
        conversation,
        localOnly,
    ) {
        appState.resolveChatCallTarget(conversationId)
    }
    val submitting by appState.chatSubmitting.collectAsState()
    val playback by appState.chatVoicePlayer.state.collectAsState()
    val recordingProgress by appState.chatVoiceRecorder.progress.collectAsState()
    val rows = remember(snapshot.account, snapshot.messages, snapshot.pendingTexts, snapshot.pendingVoices, conversationId, languageTag, callTime) {
        chatThreadRows(snapshot, conversationId, languageTag, callTime)
    }
    val latestVisibleMessageSequence = remember(rows) {
        latestMessageSequence(rows)
    }
    val byKey = remember(rows) { rows.associateBy { it.key } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val composerFocus = remember { FocusRequester() }
    var previousRecording by remember { mutableStateOf(recordingState) }
    LaunchedEffect(recordingState) {
        if (recordingState == NqrbChatRecordingState.Idle && previousRecording in setOf(
                NqrbChatRecordingState.Preview, NqrbChatRecordingState.Sending, NqrbChatRecordingState.Finalizing))
            composerFocus.requestFocus()
        previousRecording = recordingState
    }
    var previousKeys by remember(conversationId) { mutableStateOf<Set<String>>(emptySet()) }
    var initial by remember(conversationId) { mutableStateOf(true) }
    var unseen by remember(conversationId) { mutableStateOf(0) }
    var following by remember(conversationId) { mutableStateOf(true) }
    LaunchedEffect(conversationId, byKey) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }.collect { visible ->
            val sequence = visible.mapNotNull { byKey[it]?.message }.maxOfOrNull { it.sequence } ?: 0
            if (conversationId != null && sequence > 0) appState.markChatRead(conversationId, sequence)
        }
    }
    LaunchedEffect(conversationId) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, listState.layoutInfo.totalItemsCount) }.collect { (scrolling, last, total) ->
            if (scrolling) following = last >= total - 3
            if (!listState.canScrollForward) { following = true; unseen = 0 }
        }
    }
    LaunchedEffect(conversationId, rows.map { it.key }) {
        if (rows.isNotEmpty()) {
            val added = rows.filter { it.key !in previousKeys && it.date == null }
            if (initial || following || added.any { it.mine }) {
                listState.scrollToItem(rows.lastIndex)
                if ((initial || following) && conversationId != null && latestVisibleMessageSequence > 0)
                    appState.markChatRead(conversationId, latestVisibleMessageSequence)
                following = true; unseen = 0; initial = false
            } else unseen += added.size
            previousKeys = rows.map { it.key }.toSet()
        }
    }
    var text by remember(conversationId) { mutableStateOf("") }
    var editing by remember(conversationId) { mutableStateOf<PendingChatText?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = NqrbLayout.ThreadMaxWidth).fillMaxSize().imePadding()) {
        ChatHeader(
            title = displayName,
            back = strings.back,
            onBack = appState::leaveChat,
            callTarget = callTarget,
            callLabel = if (callTarget.enabled) strings.call(displayName) else strings.callUnavailable(displayName, callTarget.unavailableReason),
            onCall = { conversationId?.let(appState::requestChatCall) },
            modifier = Modifier.padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                val emptyCopy = when {
                    snapshot.syncing || snapshot.state == ChatLoadState.Loading -> strings.loadingConversation to strings.loadingConversationBody
                    snapshot.state == ChatLoadState.Error || snapshot.retryableFailure -> strings.messagesUnavailable to strings.operationFailed
                    localOnly -> strings.localConversationEmpty to strings.localConversationEmptyBody
                    else -> strings.emptyConversation to strings.emptyConversationBody
                }
                ChatStatePane(emptyCopy.first, emptyCopy.second, Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = NqrbSpacing.Md),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when {
                            row.date != null -> Text(row.date, Modifier.fillMaxWidth().padding(vertical = NqrbSpacing.Sm), style = MaterialTheme.typography.labelMedium, color = colors.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            row.message != null -> ChatMessageBubble(row.message, snapshot, strings, appState, playback, row.time, row.voice)
                            row.text != null -> PendingTextBubble(row.text, row.time, strings, appState) {
                                editing = row.text; text = row.text.text; composerFocus.requestFocus()
                            }
                            row.voice != null -> PendingVoiceBubble(row.voice, snapshot, strings, appState, playback)
                        }
                    }
                }
            }
        }
        if (unseen > 0) TextButton(onClick = { scope.launch { listState.animateScrollToItem(rows.lastIndex); following = true; unseen = 0 } },
            modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text("${strings.newMessages} · ${strings.digits(unseen.toString())}")
        }
        if (editing != null) Row(Modifier.fillMaxWidth().padding(horizontal = NqrbSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.localized("تعديل الرسالة المحفوظة", "Editing saved message"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { editing = null; text = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.cancel) }
        }
        when (recordingState) {
            NqrbChatRecordingState.Recording -> Column(
                Modifier.fillMaxWidth().padding(NqrbSpacing.Md).semantics { stateDescription = strings.recording; liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                    Box(Modifier.size(12.dp).background(colors.destructive, CircleShape))
                    Text("${strings.recording} · ${strings.duration(recordingProgress.elapsedMilliseconds)}", color = colors.textPrimary)
                }
                LinearProgressIndicator(progress = { recordingProgress.level }, Modifier.fillMaxWidth().height(3.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.limit, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                    TextButton(onClick = appState::cancelChatRecording, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.cancel) }
                    Button(onClick = appState::finishChatRecording, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.stop) }
                }
            }
            NqrbChatRecordingState.Finalizing, NqrbChatRecordingState.Sending -> Text(
                strings.finalizing, Modifier.padding(NqrbSpacing.Md).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textSecondary)
            NqrbChatRecordingState.Preview, NqrbChatRecordingState.AutoStoppedPreview -> voiceDraft?.let {
                if (recordingState == NqrbChatRecordingState.AutoStoppedPreview) Text(strings.autoStopped, Modifier.padding(NqrbSpacing.Md), color = colors.textSecondary)
                VoicePreview(it, strings, appState, playback, submitting)
            }
            else -> Row(
                Modifier.fillMaxWidth().padding(NqrbSpacing.Md), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm),
            ) {
                IconButton(onClick = appState::requestChatVoiceRecording, enabled = !submitting,
                    modifier = Modifier.size(48.dp).background(colors.elevatedSurface, CircleShape)) {
                    NqrbIcon(NqrbGlyph.Microphone, strings.record,
                        if (submitting) colors.disabledContent else colors.accent, Modifier.size(25.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4000) },
                    modifier = Modifier.weight(1f).focusRequester(composerFocus),
                    placeholder = { Text(strings.messageHint) },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !submitting,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.controlOutline,
                        disabledBorderColor = colors.border,
                        cursorColor = colors.accent,
                    ),
                )
                val sendEnabled = text.isNotBlank() && !submitting
                IconButton(
                    enabled = sendEnabled,
                    onClick = {
                        val submitted = text
                        val failed = editing
                        val queued = { if (text == submitted) text = ""; editing = null }
                        if (failed == null) appState.sendChatText(submitted, queued)
                        else appState.editFailedChatText(failed.clientMessageId, submitted, queued)
                    },
                    modifier = Modifier.size(48.dp).background(if (sendEnabled) colors.accent else colors.accentSoft, CircleShape),
                ) { NqrbIcon(NqrbGlyph.Send, strings.send,
                    if (sendEnabled) colors.callActionContent else colors.disabledContent, Modifier.size(25.dp)) }
            }
        }
        if (snapshot.retryableFailure) TextButton(onClick = appState::refreshChat, modifier = Modifier.padding(horizontal = NqrbSpacing.Md)) {
            Text(strings.retry)
        }
    }
    }

    if (recordingState == NqrbChatRecordingState.NeedsPermission) ChatDecisionDialog(
        strings.permissionTitle, strings.permissionBody, strings.continueLabel, strings.cancel,
        appState::continueChatVoicePermission, appState::dismissChatVoiceState,
    )
    if (recordingState == NqrbChatRecordingState.NeedsStorageDisclosure) ChatDecisionDialog(
        strings.storageTitle, "${strings.storageBody}\n\n${strings.temporaryBody}", strings.continueLabel, strings.cancel,
        appState::acceptChatStorageDisclosure, appState::dismissChatVoiceState,
    )
    if (recordingState in setOf(NqrbChatRecordingState.Unavailable, NqrbChatRecordingState.ActiveCall,
            NqrbChatRecordingState.CallInterrupted, NqrbChatRecordingState.BackgroundInterrupted, NqrbChatRecordingState.AudioInterrupted,
            NqrbChatRecordingState.PermissionDenied, NqrbChatRecordingState.PermissionPermanentlyDenied,
            NqrbChatRecordingState.PlaybackUnavailable, NqrbChatRecordingState.StorageUnavailable)) AlertDialog(
        onDismissRequest = appState::dismissChatVoiceState,
        title = { Text(strings.voiceNote) }, text = { Text(strings.failure(recordingState)) },
        confirmButton = { TextButton(onClick = {
            when (recordingState) {
                NqrbChatRecordingState.PermissionPermanentlyDenied -> appState.openChatMicrophoneSettings()
                NqrbChatRecordingState.CallInterrupted, NqrbChatRecordingState.BackgroundInterrupted, NqrbChatRecordingState.AudioInterrupted,
                NqrbChatRecordingState.PermissionDenied, NqrbChatRecordingState.Unavailable -> appState.requestChatVoiceRecording()
                else -> appState.dismissChatVoiceState()
            }
        }) { Text(when (recordingState) {
            NqrbChatRecordingState.PermissionPermanentlyDenied -> strings.settings
            NqrbChatRecordingState.CallInterrupted, NqrbChatRecordingState.BackgroundInterrupted, NqrbChatRecordingState.AudioInterrupted -> strings.recordAgain
            NqrbChatRecordingState.PermissionDenied, NqrbChatRecordingState.Unavailable -> strings.retry
            else -> strings.continueLabel
        }) } },
        dismissButton = { TextButton(onClick = appState::dismissChatVoiceState) { Text(strings.cancel) } },
    )
}

internal fun chatThreadRows(snapshot: ChatSnapshot, conversationId: String?, languageTag: String,
    callTime: (String, String) -> NqrbCallTime): List<ChatThreadRow> = buildList {
    var day: String? = null
    conversationId?.let(snapshot::timeline).orEmpty().forEach { activity ->
        val time = activity.createdAtUtc?.let { callTime(it, languageTag) }
        if (time != null && day != time.dayKey) {
            // A clock-skewed local date may recur; each separator belongs to its following row.
            add(ChatThreadRow("date:${activity.key}", date = time.dayLabel)); day = time.dayKey
        }
        add(ChatThreadRow(activity.key, message = activity.message, text = activity.pending?.text,
            voice = activity.pending?.voice, mine = activity.message?.senderSubjectId?.let { it == snapshot.account?.subjectId } ?: true,
            time = time?.timeLabel.orEmpty()))
    }
}

internal data class ChatThreadRow(val key: String, val message: ChatMessage? = null, val text: PendingChatText? = null,
    val voice: PendingChatVoice? = null, val date: String? = null, val mine: Boolean = false, val time: String = "")

internal fun latestMessageSequence(rows: List<ChatThreadRow>): Long =
    rows.mapNotNull { it.message }.maxOfOrNull { it.sequence } ?: 0

internal enum class ChatMessageStatus { Pending, Delivered, Read, RetryPending, Failed }

internal data class ChatMessageMetadata(
    val time: String = "",
    val status: ChatMessageStatus? = null,
    val statusLabel: String? = null,
)

private val WhatsAppReadReceiptBlue = Color(0xFF34B7F1)
private val VoiceWaveformPattern = floatArrayOf(.34f, .58f, .82f, .48f, .72f, .4f, .64f)

internal fun chatMessageMetadata(
    mine: Boolean,
    time: String,
    message: ChatMessage,
    counterpartReadSequence: Long,
    strings: NqrbChatStrings,
): ChatMessageMetadata = when {
    !mine -> ChatMessageMetadata(time = time)
    message.sequence <= counterpartReadSequence -> ChatMessageMetadata(time, ChatMessageStatus.Read, strings.read)
    message.deliveryState == "RetryPending" -> ChatMessageMetadata(time, ChatMessageStatus.RetryPending, strings.retrying)
    else -> ChatMessageMetadata(time, ChatMessageStatus.Delivered, strings.delivered)
}

@Composable
private fun ChatStatePane(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = LocalNqrbColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(
            Modifier.padding(NqrbSpacing.Md).widthIn(max = 360.dp),
            color = colors.elevatedSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Row(
                Modifier.padding(NqrbSpacing.Md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Md),
            ) {
                Box(Modifier.size(40.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                    NqrbIcon(NqrbGlyph.Chat, null, colors.accent, Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun ChatHeader(
    title: String,
    back: String,
    onBack: () -> Unit,
    callTarget: NqrbChatCallTarget? = null,
    callLabel: String = "",
    onCall: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalNqrbColors.current
    Surface(
        modifier.fillMaxWidth(),
        color = colors.elevatedSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                NqrbIcon(NqrbGlyph.Back, back, colors.textPrimary, Modifier.size(24.dp))
            }
            Box(Modifier.size(40.dp).background(colors.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                Text(title.take(1), color = colors.accent, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(NqrbSpacing.Sm))
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (callTarget != null) {
                NqrbCallIconButton(
                    label = callLabel,
                    enabled = callTarget.enabled,
                    onClick = onCall,
                )
            }
        }
    }
}

@Composable
private fun PendingVoiceBubble(pending: PendingChatVoice, snapshot: ChatSnapshot, strings: NqrbChatStrings,
    appState: NqrbAppState, playback: ChatPlayback) {
    val colors = LocalNqrbColors.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        VoiceCapsule(pending.draftToken, pending.durationMilliseconds, playback, strings, pending.failure == ChatVoiceFailure.MissingOrCorrupt,
            onPlay = { appState.playPendingChatVoice(pending) }, onStop = appState::stopChatPlayback)
        MessageMetadataFooter(
            ChatMessageMetadata(
                status = if (pending.failure == null) ChatMessageStatus.Pending else ChatMessageStatus.Failed,
                statusLabel = when (pending.failure) { null -> strings.queued; ChatVoiceFailure.Rejected -> strings.voiceRejected; ChatVoiceFailure.MissingOrCorrupt -> strings.localUnavailable },
            ),
        )
        if (pending.failure != null) Row {
            TextButton(onClick = { appState.removeFailedChatVoice(pending.clientMessageId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.remove) }
            TextButton(onClick = { appState.removeFailedChatVoice(pending.clientMessageId); appState.requestChatVoiceRecording() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.recordAgain) }
        }
    }
}

@Composable
private fun PendingTextBubble(pending: PendingChatText, time: String, strings: NqrbChatStrings,
    appState: NqrbAppState, onEdit: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        ChatTextBubble(pending.text, true, ChatMessageMetadata(time,
            if (pending.failure == null) ChatMessageStatus.Pending else ChatMessageStatus.Failed,
            pending.failure?.let(strings::textFailure) ?: strings.queued))
        if (pending.failure != null) {
            Row {
                if (pending.failure == ChatTextFailure.Forbidden)
                    TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.edit) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(pending.text)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.copy) }
                TextButton(onClick = { appState.removeFailedChatText(pending.clientMessageId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.remove) }
            }
            if (pending.failure == ChatTextFailure.Conflict)
                TextButton(onClick = { appState.reconcileFailedChatText(pending.clientMessageId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.checkDelivery) }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: ChatMessage, snapshot: ChatSnapshot, strings: NqrbChatStrings, appState: NqrbAppState,
    playback: ChatPlayback, time: String, pendingVoice: PendingChatVoice? = null) {
    val mine = message.senderSubjectId == snapshot.account?.subjectId
    val terminal = message.voiceState?.contains("Deleted") == true || message.voiceState?.contains("Expired") == true
    val local = message.voiceTransferId?.let(snapshot.localVoiceKeys::get)
    val counterpartRead = snapshot.conversations.firstOrNull { it.conversationId == message.conversationId }
        ?.counterpartLastReadSequence ?: 0
    val metadata = chatMessageMetadata(mine, time, message, counterpartRead, strings)
    if (message.kind == "voice") {
        val colors = LocalNqrbColors.current
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            VoiceCapsule(pendingVoice?.draftToken ?: local ?: message.voiceTransferId.orEmpty(), message.voiceDurationMilliseconds ?: 0, playback, strings,
                unavailable = if (pendingVoice != null) pendingVoice.failure == ChatVoiceFailure.MissingOrCorrupt else local == null && (terminal || mine), mine = mine,
                loading = message.voiceTransferId?.let(snapshot.voiceLoads::get)?.takeIf { pendingVoice == null && (local == null || it == ChatVoiceLoadState.Loading) },
                onPlay = { if (pendingVoice != null) appState.playPendingChatVoice(pendingVoice) else appState.playChatVoice(message) }, onStop = appState::stopChatPlayback)
            MessageMetadataFooter(metadata)
            if (pendingVoice != null) {
                MessageMetadataFooter(ChatMessageMetadata(status = if (pendingVoice.failure == null) ChatMessageStatus.RetryPending else ChatMessageStatus.Failed,
                    statusLabel = if (pendingVoice.failure == ChatVoiceFailure.MissingOrCorrupt) strings.localUnavailable
                        else strings.localized("لم تُحفظ النسخة المحلية بعد. التسجيل المحفوظ للإرسال ما زال متاحًا.", "Local copy not saved yet. The queued recording is still available.")))
                if (pendingVoice.failure != ChatVoiceFailure.MissingOrCorrupt)
                    TextButton(onClick = appState::refreshChat, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.retry) }
                if (pendingVoice.failure != null)
                    TextButton(onClick = { appState.removeFailedChatVoice(pendingVoice.clientMessageId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.remove) }
            }
        }
    } else ChatTextBubble(message.text.orEmpty(), mine, metadata)
}

@Composable
private fun ChatTextBubble(text: String, mine: Boolean, metadata: ChatMessageMetadata) {
    val colors = LocalNqrbColors.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(Modifier.widthIn(max = maxWidth * .84f), color = if (mine) colors.accentSoft else colors.surface, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = NqrbSpacing.Md, vertical = NqrbSpacing.Sm).semantics(mergeDescendants = true) {}) {
                Text(text, color = colors.textPrimary)
                MessageMetadataFooter(metadata, Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun MessageMetadataFooter(metadata: ChatMessageMetadata, modifier: Modifier = Modifier) {
    if (metadata.time.isBlank() && metadata.status == null) return
    val colors = LocalNqrbColors.current
    val glyph = when (metadata.status) {
        ChatMessageStatus.Delivered, ChatMessageStatus.Read -> NqrbGlyph.DoubleCheck
        ChatMessageStatus.Pending, ChatMessageStatus.RetryPending, ChatMessageStatus.Failed -> NqrbGlyph.Clock
        null -> null
    }
    val tint = when (metadata.status) {
        ChatMessageStatus.Read -> WhatsAppReadReceiptBlue
        ChatMessageStatus.RetryPending, ChatMessageStatus.Failed -> colors.destructive
        else -> colors.textSecondary
    }
    val showLabel = metadata.status in setOf(ChatMessageStatus.RetryPending, ChatMessageStatus.Failed)
    val spokenStatus = metadata.statusLabel
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(metadata.time.takeIf(String::isNotBlank), spokenStatus).joinToString(" · ")
            spokenStatus?.let { stateDescription = it }
            if (showLabel) liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (metadata.time.isNotBlank()) Text(metadata.time, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        if (showLabel && spokenStatus != null) Text(spokenStatus, style = MaterialTheme.typography.labelSmall, color = tint)
        if (glyph != null) NqrbIcon(glyph, null, tint, Modifier.size(14.dp))
    }
}

@Composable
private fun VoiceCapsule(key: String, duration: Int, playback: ChatPlayback, strings: NqrbChatStrings,
    unavailable: Boolean = false, mine: Boolean = true, onPlay: () -> Unit, onStop: () -> Unit,
    playModifier: Modifier = Modifier, loading: ChatVoiceLoadState? = null) {
    val colors = LocalNqrbColors.current
    val active = playback.takeIf { it.key == key } ?: ChatPlayback()
    val playing = active.phase == ChatPlaybackPhase.Playing
    val stateLabel = when (loading) {
        ChatVoiceLoadState.Loading -> strings.localized("جارٍ تحميل التسجيل", "Loading recording")
        ChatVoiceLoadState.RetryableFailure -> strings.localized("تعذّر تحميل التسجيل. أعد المحاولة.", "Could not load the recording. Try again.")
        ChatVoiceLoadState.Unavailable -> strings.localized("نسخة التوصيل غير متاحة.", "The delivery copy is unavailable.")
        else -> when (active.phase) {
        ChatPlaybackPhase.Preparing -> strings.preparing
        ChatPlaybackPhase.Playing -> strings.playing
        ChatPlaybackPhase.Paused -> strings.paused
        ChatPlaybackPhase.Completed -> strings.completed
        ChatPlaybackPhase.Failed -> strings.failure(NqrbChatRecordingState.PlaybackUnavailable)
        else -> if (unavailable) strings.localUnavailable else strings.voiceNote
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            Modifier.fillMaxWidth(.84f),
            color = if (mine) colors.accentSoft else colors.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(NqrbSpacing.Sm).semantics(mergeDescendants = true) {
                stateDescription = stateLabel
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
                    IconButton(onClick = onPlay, enabled = !unavailable && active.phase != ChatPlaybackPhase.Preparing && loading !in setOf(ChatVoiceLoadState.Loading, ChatVoiceLoadState.Unavailable),
                        modifier = playModifier.size(44.dp).background(colors.elevatedSurface, CircleShape)
                            .semantics { contentDescription = if (playing) strings.pause else if (loading == ChatVoiceLoadState.RetryableFailure) strings.retry else strings.play }) {
                        NqrbIcon(if (playing) NqrbGlyph.Pause else NqrbGlyph.Play, null, colors.accent, Modifier.size(23.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (loading == ChatVoiceLoadState.RetryableFailure || loading == ChatVoiceLoadState.Unavailable || unavailable || active.phase == ChatPlaybackPhase.Failed) {
                            Text(stateLabel, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium,
                                color = if (loading == ChatVoiceLoadState.RetryableFailure || active.phase == ChatPlaybackPhase.Failed) colors.destructive else colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        VoiceWaveform(
                            progress = if (duration > 0) (active.elapsedMilliseconds.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                            active = playing || active.phase in setOf(ChatPlaybackPhase.Paused, ChatPlaybackPhase.Completed),
                            enabled = !unavailable && loading != ChatVoiceLoadState.Unavailable,
                        )
                        Text(
                            strings.duration(if (active.elapsedMilliseconds > 0) active.elapsedMilliseconds else duration),
                            Modifier.semantics { contentDescription = strings.accessibleDuration(if (active.elapsedMilliseconds > 0) active.elapsedMilliseconds else duration) },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                    if (loading == ChatVoiceLoadState.Loading || active.phase in setOf(ChatPlaybackPhase.Playing, ChatPlaybackPhase.Paused, ChatPlaybackPhase.Preparing))
                        IconButton(onClick = onStop, modifier = Modifier.size(48.dp).semantics { contentDescription = strings.playbackStop }) {
                            NqrbIcon(NqrbGlyph.Close, null, colors.accent, Modifier.size(24.dp))
                        }
                }
            }
        }
    }
}

@Composable
private fun VoiceWaveform(progress: Float, active: Boolean, enabled: Boolean) {
    val colors = LocalNqrbColors.current
    val played = if (active) colors.accent else colors.textSecondary
    val remaining = if (enabled) colors.textSecondary.copy(alpha = .42f) else colors.disabledContent.copy(alpha = .45f)
    val bars = 28
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val gap = size.width / bars
        val playedBars = (bars * progress.coerceIn(0f, 1f)).toInt()
        repeat(bars) { index ->
            val heightRatio = VoiceWaveformPattern[index % VoiceWaveformPattern.size]
            val x = gap * index + gap / 2f
            val barHeight = size.height * heightRatio
            val top = (size.height - barHeight) / 2f
            drawLine(
                color = if (index < playedBars) played else remaining,
                start = Offset(x, top),
                end = Offset(x, top + barHeight),
                strokeWidth = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun VoicePreview(draft: ChatVoiceDraft, strings: NqrbChatStrings, appState: NqrbAppState, playback: ChatPlayback, submitting: Boolean) {
    val colors = LocalNqrbColors.current
    val previewFocus = remember { FocusRequester() }
    LaunchedEffect(draft.token) { previewFocus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(NqrbSpacing.Md), verticalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
        Text(strings.previewReady, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
        VoiceCapsule(draft.token, draft.durationMilliseconds, playback, strings, onPlay = appState::previewChatVoice, onStop = appState::stopChatPlayback,
            playModifier = Modifier.focusRequester(previewFocus))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NqrbSpacing.Sm)) {
            TextButton(onClick = appState::cancelChatRecording, enabled = !submitting, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.cancel) }
            Button(onClick = appState::sendChatVoice, enabled = !submitting, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(strings.sendVoice) }
        }
    }
}

@Composable
private fun ChatDecisionDialog(title: String, body: String, confirm: String, cancel: String,
    onConfirm: () -> Unit, onCancel: () -> Unit) = AlertDialog(
    onDismissRequest = onCancel,
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton = { TextButton(onClick = onCancel) { Text(cancel) } },
)
