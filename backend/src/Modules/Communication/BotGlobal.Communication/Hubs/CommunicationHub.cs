using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Contracts.Calls;
using BotGlobal.Communication.Contracts.Common;
using BotGlobal.Communication.Contracts.Messaging;
using BotGlobal.Communication.Contracts.Presence;
using BotGlobal.Communication.Realtime;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;

namespace BotGlobal.Communication.Hubs;

[Authorize]
public sealed class CommunicationHub(
    UserConnectionTracker connections,
    ICommunicationAuthorizer authorizer,
    ICommunicationPreferencesReader preferences)
    : Hub<ICommunicationClient>
{
    public override async Task OnConnectedAsync()
    {
        var userId = RequireUserId();

        connections.Connected(
            userId,
            Context.ConnectionId);

        await base.OnConnectedAsync();
    }

    public override async Task OnDisconnectedAsync(
        Exception? exception)
    {
        var userId = Context.UserIdentifier;

        if (!string.IsNullOrWhiteSpace(userId))
        {
            connections.Disconnected(
                userId,
                Context.ConnectionId);
        }

        await base.OnDisconnectedAsync(exception);
    }

    public async Task JoinConversation(
        string conversationId,
        CancellationToken cancellationToken)
    {
        var userId = RequireUserId();

        if (!await authorizer.CanAccessConversationAsync(
                userId,
                conversationId,
                cancellationToken))
        {
            throw new HubException("Conversation access denied.");
        }

        await Groups.AddToGroupAsync(
            Context.ConnectionId,
            CommunicationIds.ConversationGroup(conversationId),
            cancellationToken);
    }

    public async Task LeaveConversation(
        string conversationId,
        CancellationToken cancellationToken)
    {
        await Groups.RemoveFromGroupAsync(
            Context.ConnectionId,
            CommunicationIds.ConversationGroup(conversationId),
            cancellationToken);
    }

    public async Task SetTyping(
        string conversationId,
        bool isTyping,
        CancellationToken cancellationToken)
    {
        var userId = RequireUserId();

        if (!await authorizer.CanAccessConversationAsync(
                userId,
                conversationId,
                cancellationToken))
        {
            throw new HubException("Conversation access denied.");
        }

        await Clients
            .GroupExcept(
                CommunicationIds.ConversationGroup(conversationId),
                new[] { Context.ConnectionId })
            .TypingChanged(
                new TypingChangedEvent(
                    conversationId,
                    userId,
                    isTyping,
                    DateTimeOffset.UtcNow));
    }

    public Task<CommunicationPreferences> GetCallPreferences(
        CancellationToken cancellationToken)
        => preferences.GetAsync(
            RequireUserId(),
            cancellationToken);

    private string RequireUserId()
    {
        return Context.UserIdentifier
            ?? throw new HubException(
                "Authenticated SignalR user identifier is unavailable.");
    }
}
