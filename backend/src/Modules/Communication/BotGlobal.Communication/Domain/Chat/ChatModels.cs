using System.Security.Cryptography;
using System.Text;

namespace BotGlobal.Communication.Domain.Chat;

public static class ChatLimits
{
    public const int Subject = 200;
    public const int PairKey = 420;
    public const int ClientMessageId = 100;
    public const int Text = 4000;
    public const int Hash = 64;
    public const int FileKey = 160;
    public const long VoiceBytes = 10 * 1024 * 1024;
    public static readonly TimeSpan VoiceDuration = TimeSpan.FromMinutes(5);
}

public enum ChatMessageKind { Text = 1, Voice = 2 }
public enum ChatVoiceTransferState
{
    Uploading = 1,
    Available = 2,
    AcknowledgedDeletionPending = 3,
    AcknowledgedDeleted = 4,
    ExpiredDeletionPending = 5,
    ExpiredDeleted = 6,
}
public enum ChatDispatchState { Pending = 1, Delivered = 2, RetryPending = 3, Terminal = 4 }

public sealed class ChatConversation
{
    private ChatConversation() { }

    public ChatConversation(Guid applicationId, string firstSubjectId, string secondSubjectId, DateTimeOffset createdAtUtc)
    {
        if (applicationId == Guid.Empty) throw new ArgumentException("Application is required.", nameof(applicationId));
        var first = NormalizeSubject(firstSubjectId);
        var second = NormalizeSubject(secondSubjectId);
        if (string.Equals(first, second, StringComparison.Ordinal))
            throw new ArgumentException("A direct conversation requires two participants.");

        Id = Guid.NewGuid();
        ApplicationId = applicationId;
        if (StringComparer.Ordinal.Compare(first, second) < 0)
        {
            FirstSubjectId = first;
            SecondSubjectId = second;
        }
        else
        {
            FirstSubjectId = second;
            SecondSubjectId = first;
        }
        DirectPairKey = CreatePairKey(FirstSubjectId, SecondSubjectId);
        CreatedAtUtc = createdAtUtc;
        UpdatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public string DirectPairKey { get; private set; } = string.Empty;
    public string FirstSubjectId { get; private set; } = string.Empty;
    public string SecondSubjectId { get; private set; } = string.Empty;
    public long NextSequence { get; private set; }
    public long Version { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }
    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public bool Contains(string subjectId) =>
        string.Equals(FirstSubjectId, subjectId, StringComparison.Ordinal) ||
        string.Equals(SecondSubjectId, subjectId, StringComparison.Ordinal);

    public string Counterpart(string subjectId) =>
        string.Equals(FirstSubjectId, subjectId, StringComparison.Ordinal) ? SecondSubjectId :
        string.Equals(SecondSubjectId, subjectId, StringComparison.Ordinal) ? FirstSubjectId :
        throw new InvalidOperationException("Actor is not a conversation participant.");

    public long AllocateSequence(DateTimeOffset now)
    {
        NextSequence = checked(NextSequence + 1);
        Version = checked(Version + 1);
        UpdatedAtUtc = now;
        return NextSequence;
    }

    public static string CreatePairKey(string firstSubjectId, string secondSubjectId)
    {
        var first = NormalizeSubject(firstSubjectId);
        var second = NormalizeSubject(secondSubjectId);
        if (StringComparer.Ordinal.Compare(first, second) > 0) (first, second) = (second, first);
        return $"{first.Length}:{first}{second.Length}:{second}";
    }

    public static string NormalizeSubject(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        var normalized = value.Trim();
        if (normalized.Length > ChatLimits.Subject) throw new ArgumentOutOfRangeException(nameof(value));
        return normalized;
    }
}

public sealed class ChatMessage
{
    private ChatMessage() { }

    public ChatMessage(Guid applicationId, Guid conversationId, long sequence, string senderSubjectId,
        string clientMessageId, ChatMessageKind kind, string payloadFingerprint, DateTimeOffset createdAtUtc,
        string? text = null, Guid? voiceTransferId = null)
    {
        if (applicationId == Guid.Empty || conversationId == Guid.Empty || sequence <= 0)
            throw new ArgumentException("A scoped conversation sequence is required.");
        Id = Guid.NewGuid();
        ApplicationId = applicationId;
        ConversationId = conversationId;
        Sequence = sequence;
        SenderSubjectId = ChatConversation.NormalizeSubject(senderSubjectId);
        ClientMessageId = NormalizeClientId(clientMessageId);
        Kind = kind;
        PayloadFingerprint = payloadFingerprint;
        Text = text;
        VoiceTransferId = voiceTransferId;
        CreatedAtUtc = createdAtUtc;
    }

    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public Guid ConversationId { get; private set; }
    public long Sequence { get; private set; }
    public string SenderSubjectId { get; private set; } = string.Empty;
    public string ClientMessageId { get; private set; } = string.Empty;
    public ChatMessageKind Kind { get; private set; }
    public string PayloadFingerprint { get; private set; } = string.Empty;
    public string? Text { get; private set; }
    public Guid? VoiceTransferId { get; private set; }
    public DateTimeOffset CreatedAtUtc { get; private set; }

    public static string NormalizeClientId(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        var normalized = value.Trim();
        if (normalized.Length > ChatLimits.ClientMessageId) throw new ArgumentOutOfRangeException(nameof(value));
        return normalized;
    }

    public static string FingerprintText(string text)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(text);
        var normalized = text.Trim();
        if (normalized.Length > ChatLimits.Text) throw new ArgumentOutOfRangeException(nameof(text));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"text\n{normalized}"))).ToLowerInvariant();
    }

    public static string FingerprintVoice(string sha256, long length, int durationMilliseconds) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"voice\n{sha256}\n{length}\n{durationMilliseconds}"))).ToLowerInvariant();
}

public sealed class ChatVoiceTransfer
{
    private ChatVoiceTransfer() { }

    public ChatVoiceTransfer(Guid id, Guid applicationId, Guid conversationId, string senderSubjectId,
        string recipientSubjectId, string fileKey, string sha256, long length, int durationMilliseconds,
        DateTimeOffset publishedAtUtc, DateTimeOffset expiresAtUtc)
    {
        Id = id;
        ApplicationId = applicationId;
        ConversationId = conversationId;
        SenderSubjectId = ChatConversation.NormalizeSubject(senderSubjectId);
        RecipientSubjectId = ChatConversation.NormalizeSubject(recipientSubjectId);
        FileKey = fileKey;
        Sha256 = sha256;
        Length = length;
        DurationMilliseconds = durationMilliseconds;
        PublishedAtUtc = publishedAtUtc;
        ExpiresAtUtc = expiresAtUtc;
        State = ChatVoiceTransferState.Available;
    }

    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public Guid ConversationId { get; private set; }
    public Guid? MessageId { get; private set; }
    public string SenderSubjectId { get; private set; } = string.Empty;
    public string RecipientSubjectId { get; private set; } = string.Empty;
    public string FileKey { get; private set; } = string.Empty;
    public string Sha256 { get; private set; } = string.Empty;
    public long Length { get; private set; }
    public int DurationMilliseconds { get; private set; }
    public ChatVoiceTransferState State { get; private set; }
    public DateTimeOffset PublishedAtUtc { get; private set; }
    public DateTimeOffset ExpiresAtUtc { get; private set; }
    public DateTimeOffset? AcknowledgedAtUtc { get; private set; }
    public Guid? AcknowledgedInstallationId { get; private set; }
    public DateTimeOffset? DeletedAtUtc { get; private set; }
    public int DeleteAttempts { get; private set; }
    public string? SafeDeleteError { get; private set; }

    public bool IsDownloadable(DateTimeOffset now) => State == ChatVoiceTransferState.Available && now < ExpiresAtUtc;
    public void AttachMessage(Guid messageId) => MessageId = messageId;
    public bool Acknowledge(string subjectId, Guid? installationId, string hash, long length, DateTimeOffset now)
    {
        if (!string.Equals(RecipientSubjectId, subjectId, StringComparison.Ordinal) ||
            !string.Equals(Sha256, hash, StringComparison.Ordinal) || Length != length) return false;
        if (State is ChatVoiceTransferState.AcknowledgedDeletionPending or ChatVoiceTransferState.AcknowledgedDeleted)
            return true;
        if (State != ChatVoiceTransferState.Available) return false;
        State = ChatVoiceTransferState.AcknowledgedDeletionPending;
        AcknowledgedAtUtc = now;
        AcknowledgedInstallationId = installationId;
        return true;
    }
    public void Expire(DateTimeOffset now)
    {
        if (State == ChatVoiceTransferState.Available && now >= ExpiresAtUtc)
            State = ChatVoiceTransferState.ExpiredDeletionPending;
    }
    public void MarkDeleteFailure(string safeCode) { DeleteAttempts++; SafeDeleteError = safeCode; }
    public void MarkDeleted(DateTimeOffset now)
    {
        State = State == ChatVoiceTransferState.AcknowledgedDeletionPending
            ? ChatVoiceTransferState.AcknowledgedDeleted : ChatVoiceTransferState.ExpiredDeleted;
        DeletedAtUtc = now;
        SafeDeleteError = null;
    }
}

public sealed class ChatReceipt
{
    private ChatReceipt() { }
    public ChatReceipt(Guid applicationId, Guid conversationId, string subjectId, long sequence, DateTimeOffset now)
    { ApplicationId = applicationId; ConversationId = conversationId; SubjectId = ChatConversation.NormalizeSubject(subjectId); LastReadSequence = sequence; UpdatedAtUtc = now; }
    public Guid ApplicationId { get; private set; }
    public Guid ConversationId { get; private set; }
    public string SubjectId { get; private set; } = string.Empty;
    public long LastReadSequence { get; private set; }
    public DateTimeOffset UpdatedAtUtc { get; private set; }
    public void Advance(long sequence, DateTimeOffset now) { if (sequence > LastReadSequence) { LastReadSequence = sequence; UpdatedAtUtc = now; } }
}

public sealed class ChatDispatch
{
    private ChatDispatch() { }
    public ChatDispatch(Guid applicationId, Guid messageId, string recipientSubjectId, DateTimeOffset now)
    { Id = Guid.NewGuid(); ApplicationId = applicationId; MessageId = messageId; RecipientSubjectId = ChatConversation.NormalizeSubject(recipientSubjectId); State = ChatDispatchState.Pending; NextAttemptAtUtc = now; }
    public Guid Id { get; private set; }
    public Guid ApplicationId { get; private set; }
    public Guid MessageId { get; private set; }
    public string RecipientSubjectId { get; private set; } = string.Empty;
    public ChatDispatchState State { get; private set; }
    public int AttemptCount { get; private set; }
    public DateTimeOffset NextAttemptAtUtc { get; private set; }
    public string? SafeErrorCode { get; private set; }
    public Guid? LeaseId { get; private set; }
    public string RouteOutcomes { get; private set; } = "{}";
    public bool HintsSent { get; private set; }
    public void SetRouteOutcomes(string value) => RouteOutcomes = value;
    public void MarkHintsSent() => HintsSent = true;
    public void Delivered() { State = ChatDispatchState.Delivered; SafeErrorCode = null; }
    public void Retry(DateTimeOffset next, string code) { AttemptCount++; State = ChatDispatchState.RetryPending; NextAttemptAtUtc = next; SafeErrorCode = code; }
    public void Terminal(string code) { AttemptCount++; State = ChatDispatchState.Terminal; SafeErrorCode = code; }
}
