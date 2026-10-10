using BotGlobal.Communication.Domain.Chat;

namespace BotGlobal.Communication.Application.Chat;

public sealed record ChatConversationView(
    Guid ConversationId,
    string CounterpartSubjectId,
    string? CounterpartReference,
    string? CounterpartDisplayName,
    long LastSequence,
    long LastReadSequence,
    long CounterpartLastReadSequence,
    DateTimeOffset UpdatedAtUtc);

public sealed record ChatMessageView(
    Guid MessageId,
    Guid ConversationId,
    long Sequence,
    string SenderSubjectId,
    string ClientMessageId,
    string Kind,
    string? Text,
    Guid? VoiceTransferId,
    string? VoiceSha256,
    long? VoiceLength,
    int? VoiceDurationMilliseconds,
    string? VoiceState,
    string DeliveryState,
    DateTimeOffset CreatedAtUtc);

public sealed record ChatPage<T>(IReadOnlyList<T> Items, bool HasMore, long? NextCursor = null, string? NextConversationCursor = null);
public sealed record ChatSendResult(ChatMessageView? Message, bool Conflict = false, bool Forbidden = false);
public sealed record ChatVoiceUpload(string FileKey, string Sha256, long Length, int DurationMilliseconds = 0);

public interface IChatEngine
{
    Task<ChatConversationView?> CreateOrGetDirectAsync(string reference, CancellationToken cancellationToken);
    Task<ChatPage<ChatConversationView>> ListConversationsAsync(DateTimeOffset? before, int take, CancellationToken cancellationToken, string? cursor = null);
    Task<ChatPage<ChatMessageView>?> ListMessagesAsync(Guid conversationId, long afterSequence, int take, CancellationToken cancellationToken);
    Task<ChatSendResult> SendTextAsync(Guid conversationId, string clientMessageId, string text, CancellationToken cancellationToken);
    Task<ChatSendResult> SendVoiceAsync(Guid conversationId, string clientMessageId, Stream content, string contentType, int durationMilliseconds, CancellationToken cancellationToken);
    Task<(ChatVoiceTransfer Transfer, Stream Content)?> DownloadVoiceAsync(Guid transferId, CancellationToken cancellationToken);
    Task<bool> AcknowledgeVoiceAsync(Guid transferId, Guid installationId, string sha256, long length, CancellationToken cancellationToken);
    Task<long?> AdvanceReadReceiptAsync(Guid conversationId, long sequence, CancellationToken cancellationToken);
}
