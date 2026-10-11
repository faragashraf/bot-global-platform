using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using System.Security.Cryptography;
using System.Text;

namespace BotGlobal.Communication.Hubs;

[Authorize(AuthenticationSchemes = ApplicationIdentityDefaults.Scheme + "," + MobileDeviceAuthenticationDefaults.Scheme)]
public sealed class ChatHub(IChatActorResolver actors, ChatPolicyRegistry policies, ChatConnectionRegistry connections,
    CommunicationDbContext db, TimeProvider clock, IServiceProvider services) : Hub
{
    public override async Task OnConnectedAsync()
    {
        var actor = Context.User is null ? null : await actors.ResolveAsync(Context.User, Context.ConnectionAborted);
        var adapters = actor is null ? null : policies.Resolve(actor.Application.ApplicationKey);
        var credential = actor is null ? null : Context.GetHttpContext()?.Items[ChatConnectionCredential.Key(actor.Mechanism)] as ChatConnectionCredential;
        if (actor is null || credential is null || adapters is null ||
            await adapters.Value.Directory.FindBySubjectAsync(actor.Application, actor.SubjectId, Context.ConnectionAborted) is null)
        {
            Context.Abort();
            return;
        }
        var connection = Context;
        connections.Add(Context.ConnectionId, actor, Context.User!, credential, connection.Abort);
        await base.OnConnectedAsync();
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    { connections.Remove(Context.ConnectionId); return base.OnDisconnectedAsync(exception); }

    public async Task SetChatPresence(ChatPresenceRequest request)
    {
        var state = ChatPresenceStates.Normalize(request.State);
        if (request.ConversationId == Guid.Empty || state is null)
            throw new HubException("Invalid chat presence.");
        var actor = Context.User is null ? null : await actors.ResolveAsync(Context.User, Context.ConnectionAborted);
        var adapters = actor is null ? null : policies.Resolve(actor.Application.ApplicationKey);
        if (actor is null || adapters is null)
            throw new HubException("Chat authentication required.");
        var conversation = await db.ChatConversations.AsNoTracking().SingleOrDefaultAsync(x =>
            x.ApplicationId == actor.Application.ApplicationId && x.Id == request.ConversationId &&
            (x.FirstSubjectId == actor.SubjectId || x.SecondSubjectId == actor.SubjectId), Context.ConnectionAborted);
        if (conversation is null)
            throw new HubException("Conversation access denied.");
        var self = await adapters.Value.Directory.FindBySubjectAsync(actor.Application, actor.SubjectId, Context.ConnectionAborted);
        var counterpartSubject = conversation.Counterpart(actor.SubjectId);
        var counterpart = await adapters.Value.Directory.FindBySubjectAsync(actor.Application, counterpartSubject, Context.ConnectionAborted);
        if (self is null || counterpart is null ||
            !await adapters.Value.Policy.CanStartDirectConversationAsync(actor, self, counterpart, Context.ConnectionAborted))
            throw new HubException("Conversation access denied.");

        var now = clock.GetUtcNow();
        var expiresAt = state == ChatPresenceStates.Idle ? now : now.AddSeconds(8);
        var changed = new ChatPresenceChangedEvent(actor.Application.ApplicationId, conversation.Id, actor.SubjectId,
            state, now, expiresAt);
        await connections.SendPresenceAsync(changed, counterpartSubject, services, Clients.Client, Context.ConnectionAborted);
    }

    public static string SubjectGroup(Guid applicationId, string subjectId)
    {
        var subjectHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(subjectId))).ToLowerInvariant();
        return $"chat:{applicationId:N}:{subjectHash}";
    }
}
