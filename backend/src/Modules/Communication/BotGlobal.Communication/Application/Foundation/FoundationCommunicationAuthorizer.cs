using BotGlobal.Communication.Application.Abstractions;

namespace BotGlobal.Communication.Application.Foundation;

internal sealed class FoundationCommunicationAuthorizer
    : ICommunicationAuthorizer
{
    public Task<bool> CanAccessConversationAsync(
        string userId,
        string conversationId,
        CancellationToken cancellationToken = default)
        => Task.FromResult(false);

    public Task<bool> CanContactUserAsync(
        string userId,
        string targetUserId,
        CancellationToken cancellationToken = default)
        => Task.FromResult(false);
}
