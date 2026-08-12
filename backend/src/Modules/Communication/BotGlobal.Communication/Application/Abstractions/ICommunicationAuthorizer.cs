namespace BotGlobal.Communication.Application.Abstractions;

public interface ICommunicationAuthorizer
{
    Task<bool> CanAccessConversationAsync(
        string userId,
        string conversationId,
        CancellationToken cancellationToken = default);

    Task<bool> CanContactUserAsync(
        string userId,
        string targetUserId,
        CancellationToken cancellationToken = default);
}
