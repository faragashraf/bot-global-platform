using BotGlobal.Contracts.Calling;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Calling.Realtime;

internal sealed class CallExpiryBackgroundService(
    CallSessionRegistry sessions,
    IHubContext<CallingHub> hub,
    IServiceScopeFactory scopes,
    TimeProvider timeProvider,
    ILogger<CallExpiryBackgroundService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1), timeProvider);
        while (await timer.WaitForNextTickAsync(stoppingToken))
            await ProcessExpiredCallsAsync(timeProvider.GetUtcNow(), stoppingToken);
    }

    internal async Task ProcessExpiredCallsAsync(
        DateTimeOffset now,
        CancellationToken cancellationToken)
    {
        foreach (var session in sessions.Expire(now))
        {
            foreach (var participant in sessions.ConnectedParticipants(session.CallerMembershipId, session.ApplicationKey))
                await hub.Clients.Client(participant.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(session.CallId, "expired"), cancellationToken);
            foreach (var participant in sessions.ConnectedParticipants(session.CalleeMembershipId, session.ApplicationKey))
                await hub.Clients.Client(participant.ConnectionId).SendAsync("CallEnded", new CallEndedEvent(session.CallId, "expired"), cancellationToken);
            try
            {
                await using var scope = scopes.CreateAsyncScope();
                var dispatcher = scope.ServiceProvider.GetRequiredService<IIncomingCallNotificationDispatcher>();
                await dispatcher.DispatchAsync(new IncomingCallNotification(session.ApplicationKey, session.CalleeSubjectId,
                    session.CallId, IncomingCallNotificationKind.Expired, session.CallerDisplayName, session.ExpiresAtUtc), cancellationToken);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                logger.LogWarning("Expired call notification failed. ErrorType={ErrorType}", error.GetType().Name);
            }
        }
    }
}
