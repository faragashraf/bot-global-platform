using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Games.Realtime;

internal sealed class RevokedGamePresenceCleanupService(
    GameConnectionRegistry connections,
    IHubContext<GamesHub> hub,
    ILogger<RevokedGamePresenceCleanupService> logger,
    TimeProvider? timeProvider = null) : BackgroundService
{
    private readonly TimeProvider _timeProvider = timeProvider ?? TimeProvider.System;
    private const int MaximumRoutesPerPass = 64;
    private static readonly TimeSpan RetryInterval = TimeSpan.FromSeconds(2);

    internal async Task<int> DrainOnceAsync(CancellationToken cancellationToken)
    {
        var completed = 0;
        foreach (var route in connections.PendingRevokedPresenceDue(
            MaximumRoutesPerPass,
            _timeProvider.GetUtcNow()))
        {
            try
            {
                await hub.Groups.RemoveFromGroupAsync(
                    route.ConnectionId,
                    GamesHub.GroupName(route.SessionId),
                    cancellationToken);
                // Record identity is part of equality. Completion of an older removal
                // therefore cannot acknowledge a later add for the same route tuple.
                connections.CompleteRevokedPresence(route);
                completed++;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // Retry forever at a bounded interval. Reaching any attempt count
                // can never orphan the route, while the delay prevents a
                // permanently failing SignalR route from creating a hot loop.
                connections.RecordRevokedPresenceFailure(
                    route,
                    _timeProvider.GetUtcNow());
                // Do not log connection, membership, or session identifiers.
                logger.LogWarning("A revoked game group route could not be removed; cleanup will retry");
            }
        }

        return completed;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            await DrainOnceAsync(stoppingToken);
            await Task.Delay(RetryInterval, stoppingToken);
        }
    }
}
