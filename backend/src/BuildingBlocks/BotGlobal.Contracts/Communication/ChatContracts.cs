using System.Security.Claims;

namespace BotGlobal.Contracts.Communication;

public static class ChatContract
{
    public const string HubPath = "/hubs/chat";
    public const string MessageEvent = "chat_message";
    public const int SubjectMaxLength = 200;
    public const int ReferenceMaxLength = 200;
    public const int ClientMessageIdMaxLength = 100;
    public const int TextMaxLength = 4000;
}

// Ephemeral authentication capability created by the owning authentication handler.
// Its delegate closes over the runtime credential; never serialize or log it.
public sealed class ChatConnectionCredential(Func<IServiceProvider, CancellationToken, Task<bool>> validate)
{
    public static string Key(ChatActorMechanism mechanism) => $"chat-credential:{mechanism}";
    public Task<bool> ValidateAsync(IServiceProvider services, CancellationToken token) => validate(services, token);
    public override string ToString() => nameof(ChatConnectionCredential);
}

public sealed record ChatApplication(Guid ApplicationId, string ApplicationKey);

public enum ChatActorMechanism
{
    ApplicationSession = 1,
    PairedDevice = 2,
}

public sealed record ChatActor(
    ChatApplication Application,
    string SubjectId,
    ChatActorMechanism Mechanism,
    Guid? InstallationId = null);

public sealed record ChatParticipant(
    string SubjectId,
    string Reference,
    string DisplayName);

public interface IChatParticipantDirectory
{
    string ApplicationKey { get; }

    Task<ChatParticipant?> FindByReferenceAsync(
        ChatApplication application,
        string opaqueReference,
        CancellationToken cancellationToken);

    Task<ChatParticipant?> FindBySubjectAsync(
        ChatApplication application,
        string subjectId,
        CancellationToken cancellationToken);
}

public interface IChatAccessPolicy
{
    string ApplicationKey { get; }

    Task<bool> CanStartDirectConversationAsync(
        ChatActor actor,
        ChatParticipant actorParticipant,
        ChatParticipant counterpart,
        CancellationToken cancellationToken);

    Task<bool> IsBidirectionallyBlockedAsync(
        ChatApplication application,
        ChatParticipant first,
        ChatParticipant second,
        CancellationToken cancellationToken);
}

public interface IChatActorResolver
{
    Task<ChatActor?> ResolveAsync(
        ClaimsPrincipal principal,
        CancellationToken cancellationToken);
}

public sealed record ChatMessageHint(
    Guid ApplicationId,
    Guid ConversationId,
    Guid MessageId,
    long Sequence,
    string Kind,
    DateTimeOffset CreatedAtUtc);
