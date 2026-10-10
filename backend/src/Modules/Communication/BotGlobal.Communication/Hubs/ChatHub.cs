using BotGlobal.Communication.Application.Chat;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using System.Security.Cryptography;
using System.Text;

namespace BotGlobal.Communication.Hubs;

[Authorize(AuthenticationSchemes = ApplicationIdentityDefaults.Scheme + "," + MobileDeviceAuthenticationDefaults.Scheme)]
public sealed class ChatHub(IChatActorResolver actors, ChatPolicyRegistry policies, ChatConnectionRegistry connections) : Hub
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

    public static string SubjectGroup(Guid applicationId, string subjectId)
    {
        var subjectHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(subjectId))).ToLowerInvariant();
        return $"chat:{applicationId:N}:{subjectHash}";
    }
}
