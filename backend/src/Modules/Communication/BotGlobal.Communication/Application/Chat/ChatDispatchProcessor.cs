using System.Text.Json;
using BotGlobal.Communication.Application.MobileNotifications.Push;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Domain.Chat;
using BotGlobal.Communication.Hubs;
using BotGlobal.Communication.Infrastructure.Persistence;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Communication.Application.Chat;

internal sealed class ChatDispatchProcessor(
    CommunicationDbContext db, ChatPolicyRegistry registry, IMobileRecipientResolver recipients,
    IMobilePushDestinationResolver destinations, IApplicationPushNotificationDispatcher push,
    ChatConnectionRegistry connections, IHubContext<ChatHub> hub, IServiceProvider services, TimeProvider clock)
{
    // Durable per-device outcomes contain opaque installation IDs only, never tokens/payloads.
    // Unknown means the provider may have accepted: don't manufacture exactly-once or resend it.
    internal enum RouteOutcome { InFlight, Accepted, Retry, Permanent, Unknown }
    public async Task ProcessAsync(CancellationToken token)
    {
        for (var batch = 0; batch < 10; batch++)
        {
            var now = clock.GetUtcNow();
            var ids = await db.ChatDispatches.AsNoTracking().Where(x =>
                (x.State == ChatDispatchState.Pending || x.State == ChatDispatchState.RetryPending) && x.NextAttemptAtUtc <= now)
                .OrderBy(x => x.NextAttemptAtUtc).ThenBy(x => x.Id).Select(x => x.Id).Take(50).ToListAsync(token);
            if (ids.Count == 0) return;
            foreach (var id in ids)
            {
                var lease = Guid.NewGuid(); now = clock.GetUtcNow();
                var claimed = await db.ChatDispatches.Where(x => x.Id == id &&
                    (x.State == ChatDispatchState.Pending || x.State == ChatDispatchState.RetryPending) && x.NextAttemptAtUtc <= now)
                    .ExecuteUpdateAsync(s => s.SetProperty(x => x.LeaseId, lease)
                        .SetProperty(x => x.NextAttemptAtUtc, now.AddMinutes(2)), token);
                if (claimed == 0) continue;
                db.ChangeTracker.Clear();
                var dispatch = await db.ChatDispatches.SingleAsync(x => x.Id == id && x.LeaseId == lease, token);
                try { await SendAsync(dispatch, lease, token); }
                catch (OperationCanceledException) when (token.IsCancellationRequested) { throw; }
                catch (DbUpdateConcurrencyException) { db.ChangeTracker.Clear(); }
                catch (Exception)
                {
                    dispatch.Retry(clock.GetUtcNow().AddSeconds(30), "chat_dispatch_recover");
                    if (dispatch.AttemptCount >= 5) dispatch.Terminal("chat_dispatch_retry_exhausted");
                    await db.SaveChangesAsync(token);
                }
            }
        }
    }

    private async Task SendAsync(ChatDispatch dispatch, Guid lease, CancellationToken token)
    {
        var message = await db.ChatMessages.AsNoTracking().SingleAsync(x => x.ApplicationId == dispatch.ApplicationId && x.Id == dispatch.MessageId, token);
        var conversation = await db.ChatConversations.AsNoTracking().SingleAsync(x => x.ApplicationId == dispatch.ApplicationId && x.Id == message.ConversationId, token);
        var key = await registry.ResolveByApplicationIdAsync(dispatch.ApplicationId, token);
        var adapters = key is null ? null : registry.Resolve(key);
        var application = new ChatApplication(dispatch.ApplicationId, key ?? "");
        var first = adapters is null ? null : await adapters.Value.Directory.FindBySubjectAsync(application, conversation.FirstSubjectId, token);
        var second = adapters is null ? null : await adapters.Value.Directory.FindBySubjectAsync(application, conversation.SecondSubjectId, token);
        if (adapters is null || first is null || second is null || await adapters.Value.Policy.IsBidirectionallyBlockedAsync(application, first, second, token))
        { dispatch.Terminal("chat_delivery_forbidden"); await db.SaveChangesAsync(token); return; }
        var hint = new ChatMessageHint(dispatch.ApplicationId, conversation.Id, message.Id, message.Sequence,
            message.Kind == ChatMessageKind.Text ? "text" : "voice", message.CreatedAtUtc);
        if (!dispatch.HintsSent)
        {
            // Persist intent first: a crash may lose a hint, never canonical history.
            dispatch.MarkHintsSent(); await db.SaveChangesAsync(token);
            await connections.SendAsync(hint, dispatch.RecipientSubjectId, services, hub, token);
        }
        var app = new NotificationApplicationContext(dispatch.ApplicationId);
        var devices = await recipients.ResolveActiveDevicesAsync(app, dispatch.RecipientSubjectId, token);
        var routes = JsonSerializer.Deserialize<Dictionary<Guid, RouteOutcome>>(dispatch.RouteOutcomes) ?? [];
        foreach (var old in routes.Where(x => x.Value == RouteOutcome.InFlight).Select(x => x.Key).ToArray()) routes[old] = RouteOutcome.Unknown;
        foreach (var device in devices.Take(100))
        {
            if (routes.TryGetValue(device.DeviceId, out var outcome) && outcome != RouteOutcome.Retry) continue;
            var destination = await destinations.ResolveActiveAsync(app, device.DeviceId, PushProviderNames.FirebaseCloudMessaging, token);
            if (destination is null) { routes[device.DeviceId] = RouteOutcome.Retry; continue; }
            // Renew/check ownership before each externally visible send; bounded provider timeout < lease.
            var now = clock.GetUtcNow();
            if (await db.ChatDispatches.Where(x => x.Id == dispatch.Id && x.LeaseId == lease && x.NextAttemptAtUtc > now)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.NextAttemptAtUtc, now.AddMinutes(2)), token) == 0) return;
            routes[device.DeviceId] = RouteOutcome.InFlight;
            dispatch.SetRouteOutcomes(JsonSerializer.Serialize(routes)); await db.SaveChangesAsync(token);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token); timeout.CancelAfter(TimeSpan.FromSeconds(15));
            ApplicationPushDispatchResult result;
            try
            {
                result = await push.DispatchAsync(new ApplicationPushMessage(app, destination.Provider, destination.RegistrationToken,
                    "رسالة جديدة", "افتح التطبيق لمزامنة المحادثة.", new Dictionary<string, string> {
                        ["notificationId"] = message.Id.ToString("N"), ["type"] = ChatContract.MessageEvent,
                        ["applicationId"] = dispatch.ApplicationId.ToString("D"), ["conversationId"] = conversation.Id.ToString("D"),
                        ["messageId"] = message.Id.ToString("D"), ["sequence"] = message.Sequence.ToString(System.Globalization.CultureInfo.InvariantCulture),
                        ["kind"] = hint.Kind, ["titleEn"] = "New message", ["bodyEn"] = "Open the app to sync the conversation." },
                    TimeSpan.FromHours(24)), timeout.Token);
            }
            catch (OperationCanceledException) when (!token.IsCancellationRequested) { result = new(ApplicationPushDispatchKind.Ambiguous); }
            routes[device.DeviceId] = result.Kind switch {
                ApplicationPushDispatchKind.Accepted => RouteOutcome.Accepted,
                ApplicationPushDispatchKind.PermanentFailure or ApplicationPushDispatchKind.ProviderDisabled or ApplicationPushDispatchKind.MissingConfiguration => RouteOutcome.Permanent,
                ApplicationPushDispatchKind.Ambiguous => RouteOutcome.Unknown,
                _ => RouteOutcome.Retry };
            dispatch.SetRouteOutcomes(JsonSerializer.Serialize(routes)); await db.SaveChangesAsync(token);
        }
        dispatch.SetRouteOutcomes(JsonSerializer.Serialize(routes));
        if (devices.Count == 0 || routes.Values.Any(x => x == RouteOutcome.Retry))
        {
            if (dispatch.AttemptCount >= 4) dispatch.Terminal("chat_dispatch_retry_exhausted");
            else dispatch.Retry(clock.GetUtcNow().AddSeconds(Math.Pow(2, dispatch.AttemptCount + 1)), "chat_route_retry");
        }
        else if (routes.Values.All(x => x == RouteOutcome.Accepted)) dispatch.Delivered();
        else dispatch.Terminal("chat_routes_partial_or_unknown");
        await db.SaveChangesAsync(token);
    }
}

internal sealed class ChatDispatchBackgroundService(IServiceScopeFactory scopes, ILogger<ChatDispatchBackgroundService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken token)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        while (!token.IsCancellationRequested)
        {
            try { await using var scope = scopes.CreateAsyncScope(); await scope.ServiceProvider.GetRequiredService<ChatDispatchProcessor>().ProcessAsync(token); }
            catch (OperationCanceledException) when (token.IsCancellationRequested) { break; }
            catch (Exception error) { logger.LogWarning("Chat dispatch interrupted. ErrorType={ErrorType}", error.GetType().Name); }
            if (!await timer.WaitForNextTickAsync(token)) break;
        }
    }
}
