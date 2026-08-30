using System.Collections.Concurrent;
using BotGlobal.Contracts.Notifications;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.Notifications.Application.Processing;

internal sealed class NotificationCampaignBackgroundService(
    IServiceScopeFactory scopeFactory,
    IOptions<NotificationCampaignOptions> options,
    TimeProvider timeProvider,
    ILogger<NotificationCampaignBackgroundService> logger)
    : BackgroundService
{
    protected override async Task ExecuteAsync(
        CancellationToken stoppingToken)
    {
        var pollInterval = TimeSpan.FromSeconds(
            options.Value.Worker.PollIntervalSeconds);

        while (!stoppingToken.IsCancellationRequested)
        {
            var didWork = false;

            try
            {
                didWork = await RunIterationAsync(stoppingToken);
            }
            catch (OperationCanceledException)
                when (stoppingToken.IsCancellationRequested)
            {
                break;
            }
            catch (Exception exception)
            {
                logger.LogError(
                    "Notification campaign worker iteration failed. SafeErrorType={SafeErrorType}",
                    exception.GetType().Name);
            }

            if (!didWork)
            {
                await Task.Delay(
                    pollInterval,
                    timeProvider,
                    stoppingToken);
            }
        }
    }

    internal async Task<bool> RunIterationAsync(
        CancellationToken cancellationToken)
    {
        var configured = options.Value.Worker;
        var now = timeProvider.GetUtcNow();
        var leaseExpiry = now.AddSeconds(configured.LeaseSeconds);
        var didWork = false;

        using (var expiryScope = scopeFactory.CreateScope())
        {
            var expiry = expiryScope.ServiceProvider
                .GetRequiredService<NotificationExpiryProcessor>();

            didWork |= await expiry.ExpireUnexpandedCampaignAsync(
                now,
                cancellationToken);
        }

        using (var summaryRepairScope = scopeFactory.CreateScope())
        {
            didWork |= await summaryRepairScope.ServiceProvider
                .GetRequiredService<NotificationCampaignSummaryService>()
                .RefreshNextDispatchingAsync(
                    now,
                    cancellationToken);
        }

        ClaimedNotificationWork? audienceClaim;
        using (var claimScope = scopeFactory.CreateScope())
        {
            audienceClaim = await claimScope.ServiceProvider
                .GetRequiredService<NotificationWorkClaimer>()
                .ClaimAudienceAsync(
                    now,
                    leaseExpiry,
                    cancellationToken);

            if (audienceClaim is not null)
            {
                didWork |= await claimScope.ServiceProvider
                    .GetRequiredService<NotificationAudienceExpander>()
                    .ExpandClaimedPageAsync(
                        audienceClaim,
                        configured.BatchSize,
                        now,
                        cancellationToken);
            }
        }

        IReadOnlySet<Guid> expiredCampaigns;
        using (var expiryScope = scopeFactory.CreateScope())
        {
            expiredCampaigns = await expiryScope.ServiceProvider
                .GetRequiredService<NotificationExpiryProcessor>()
                .ExpireBatchAsync(
                    now,
                    configured.BatchSize,
                    cancellationToken);
            didWork |= expiredCampaigns.Count > 0;
        }

        IReadOnlySet<Guid> recoveredCampaigns;
        using (var recoveryScope = scopeFactory.CreateScope())
        {
            recoveredCampaigns = await recoveryScope.ServiceProvider
                .GetRequiredService<NotificationDeliveryRecoveryProcessor>()
                .RecoverBatchAsync(
                    now,
                    configured.BatchSize,
                    cancellationToken);
            didWork |= recoveredCampaigns.Count > 0;
        }

        IReadOnlyList<ClaimedNotificationWork> recipientClaims;
        using (var claimScope = scopeFactory.CreateScope())
        {
            recipientClaims = await claimScope.ServiceProvider
                .GetRequiredService<NotificationWorkClaimer>()
                .ClaimRecipientsAsync(
                    now,
                    leaseExpiry,
                    configured.BatchSize,
                    cancellationToken);
        }

        var results = new ConcurrentBag<NotificationAttemptResult>();

        await Parallel.ForEachAsync(
            recipientClaims,
            new ParallelOptions
            {
                CancellationToken = cancellationToken,
                MaxDegreeOfParallelism = configured.MaxParallelDeliveries
            },
            async (claim, token) =>
            {
                try
                {
                    using var attemptScope = scopeFactory.CreateScope();
                    var result = await attemptScope.ServiceProvider
                        .GetRequiredService<NotificationDeliveryAttemptProcessor>()
                        .ProcessAsync(claim, token);
                    results.Add(result);
                }
                catch (OperationCanceledException)
                    when (token.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception exception)
                {
                    logger.LogError(
                        "An isolated notification recipient attempt failed. SafeErrorType={SafeErrorType}",
                        exception.GetType().Name);
                }
            });

        didWork |= recipientClaims.Count > 0;

        var affectedCampaignIds = results
            .Where(result => result.CampaignId != Guid.Empty)
            .Select(result => result.CampaignId)
            .Concat(expiredCampaigns)
            .Concat(recoveredCampaigns)
            .Distinct()
            .ToArray();

        foreach (var campaignId in affectedCampaignIds)
        {
            using var summaryScope = scopeFactory.CreateScope();
            await summaryScope.ServiceProvider
                .GetRequiredService<NotificationCampaignSummaryService>()
                .RefreshAsync(
                    campaignId,
                    timeProvider.GetUtcNow(),
                    cancellationToken);
        }

        if (results.Count > 0)
        {
            var grouped = results
                .Where(result => result.Outcome.HasValue)
                .GroupBy(result => result.Outcome!.Value)
                .ToDictionary(group => group.Key, group => group.Count());

            foreach (var outcome in grouped)
            {
                logger.LogInformation(
                    "Notification campaign worker processed a delivery batch outcome. Outcome={Outcome} Count={Count}",
                    outcome.Key,
                    outcome.Value);
            }
        }

        return didWork;
    }
}
