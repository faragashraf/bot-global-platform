using System.Collections.Concurrent;
using System.Security.Claims;
using BotGlobal.Contracts.Communication;
using BotGlobal.Communication.Hubs;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.Communication.Application.Chat;

// No unvalidated subject group broadcasts. Each currently connected credential is
// checked through a fresh authoritative module service immediately before its hint.
public sealed class ChatConnectionRegistry
{
    private sealed record Subscription(ChatActor Actor, ClaimsPrincipal Principal, ChatConnectionCredential Credential, Action Abort);
    private readonly ConcurrentDictionary<string, Subscription> _connections = new();
    public void Add(string id, ChatActor actor, ClaimsPrincipal principal, ChatConnectionCredential credential, Action abort) =>
        _connections[id] = new(actor, principal, credential, abort);
    public void Remove(string id) => _connections.TryRemove(id, out _);

    public async Task SendAsync(ChatMessageHint hint, string subject, IServiceProvider services, IHubContext<ChatHub> hub, CancellationToken token)
    {
        foreach (var (id, subscription) in _connections.ToArray())
        {
            if (subscription.Actor.Application.ApplicationId != hint.ApplicationId || subscription.Actor.SubjectId != subject) continue;
            var valid = false;
            try
            {
                valid = await subscription.Credential.ValidateAsync(services, token) &&
                    await services.GetRequiredService<IChatActorResolver>().ResolveAsync(subscription.Principal, token) == subscription.Actor;
                if (valid)
                {
                    var adapters = services.GetRequiredService<ChatPolicyRegistry>().Resolve(subscription.Actor.Application.ApplicationKey);
                    valid = adapters is not null && await adapters.Value.Directory.FindBySubjectAsync(subscription.Actor.Application, subject, token) is not null;
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { throw; }
            catch { valid = false; }
            if (!valid) { Remove(id); subscription.Abort(); continue; }
            if (_connections.TryGetValue(id, out var current) && ReferenceEquals(current, subscription))
                await hub.Clients.Client(id).SendAsync(ChatContract.MessageEvent, hint, token);
        }
    }
}
