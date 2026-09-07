using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignProcessingTests
{
    [Theory]
    [InlineData(MobileNotificationTransportOutcomeKind.NoAvailableRoute)]
    [InlineData(MobileNotificationTransportOutcomeKind.TransientFailure)]
    public async Task Retry_budget_terminalizes_route_absence_and_transient_failure(
        MobileNotificationTransportOutcomeKind outcome)
    {
        var now = new DateTimeOffset(2026, 1, 10, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"retry-budget-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var time = new MutableTimeProvider(now);
        var processor = new NotificationDeliveryAttemptProcessor(
            db, new FixedTransport(outcome), Options.Create(OptionsForTests()), time,
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);

        for (var number = 1; number <= 8; number++)
        {
            var claim = Assert.Single(await new NotificationWorkClaimer(db).ClaimRecipientsAsync(
                time.GetUtcNow(), time.GetUtcNow().AddMinutes(2), 10, CancellationToken.None));
            await processor.ProcessAsync(claim, CancellationToken.None);
            if (number < 8)
                time.Set(recipient.NextAttemptAtUtc!.Value);
        }

        Assert.Equal(NotificationRecipientStatus.FailedPermanent, recipient.Status);
        Assert.Equal("retry-budget-exhausted", recipient.LastSafeErrorCode);
        Assert.Null(recipient.NextAttemptAtUtc);
        Assert.Equal(8, recipient.AttemptCount);
        Assert.Equal(8, await db.DeliveryAttempts.CountAsync());
        Assert.All(await db.DeliveryAttempts.ToArrayAsync(), attempt =>
            Assert.Equal(NotificationDeliveryAttemptStatus.RetryableFailure, attempt.Status));
        Assert.Empty(await new NotificationWorkClaimer(db).ClaimRecipientsAsync(
            time.GetUtcNow().AddDays(1), time.GetUtcNow().AddDays(1).AddMinutes(2), 10, CancellationToken.None));
    }

    [Fact]
    public async Task Completed_empty_audience_is_finalized_by_recovery_poll_once()
    {
        var now = new DateTimeOffset(2026, 1, 10, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"empty-summary-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        db.Campaigns.Add(campaign);
        await db.SaveChangesAsync();
        var logger = new RecordingLogger<NotificationCampaignSummaryService>();
        var summary = new NotificationCampaignSummaryService(db, logger);

        Assert.True(await summary.RefreshNextDispatchingAsync(now, CancellationToken.None));
        Assert.Equal(NotificationCampaignStatus.Completed, campaign.Status);
        Assert.Equal(now, campaign.CompletedAtUtc);
        Assert.Equal(0, campaign.PendingCount + campaign.FcmAcceptedCount + campaign.SignalRDispatchedCount
            + campaign.FailedCount + campaign.SkippedCount + campaign.ExpiredCount);
        Assert.False(await summary.RefreshNextDispatchingAsync(now.AddSeconds(10), CancellationToken.None));
        await summary.RefreshAsync(campaign.Id, now.AddSeconds(20), CancellationToken.None);
        Assert.Single(logger.Messages, message => message.Contains("completed with an empty audience"));
        Assert.Equal(now, campaign.CompletedAtUtc);
        Assert.False(db.ChangeTracker.HasChanges());
        Assert.Empty(await db.Recipients.ToArrayAsync());
        Assert.Empty(await db.DeliveryAttempts.ToArrayAsync());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Empty_audience_and_existing_empty_campaign_recover_through_worker_poll(bool alreadyExpanded)
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"empty-worker-{Guid.NewGuid():N}";
        var time = new MutableTimeProvider(new DateTimeOffset(2026, 1, 10, 10, 0, 0, TimeSpan.Zero));
        await using (var seed = Context(root, name))
        {
            seed.Campaigns.Add(alreadyExpanded ? DispatchingCampaign(time.GetUtcNow()) : Campaign(time.GetUtcNow()));
            await seed.SaveChangesAsync();
        }

        var transport = new RecordingTransport();
        using var provider = BuildWorkerProvider(root, name, time, transport);
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);

        await using var verify = Context(root, name);
        Assert.Equal(NotificationCampaignStatus.Completed, (await verify.Campaigns.SingleAsync()).Status);
        Assert.Equal(0, transport.Calls);
        Assert.Empty(await verify.Recipients.ToArrayAsync());
        Assert.Empty(await verify.DeliveryAttempts.ToArrayAsync());
    }

    [Fact]
    public async Task Incomplete_and_failed_audience_are_not_successful_empty_campaigns()
    {
        var now = new DateTimeOffset(2026, 1, 10, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"incomplete-{Guid.NewGuid():N}");
        var campaign = Campaign(now);
        db.Campaigns.Add(campaign);
        await db.SaveChangesAsync();
        var summary = new NotificationCampaignSummaryService(db);
        Assert.False(await summary.RefreshNextDispatchingAsync(now, CancellationToken.None));
        await summary.RefreshAsync(campaign.Id, now, CancellationToken.None);
        Assert.Equal(NotificationCampaignStatus.Queued, campaign.Status);

        var claim = await new NotificationWorkClaimer(db).ClaimAudienceAsync(now, now.AddMinutes(2), CancellationToken.None);
        await Assert.ThrowsAsync<InvalidOperationException>(() => new NotificationAudienceExpander(db,
            new PagedAudienceReader([], fail: true)).ExpandClaimedPageAsync(claim!, 100, now, CancellationToken.None));
        Assert.False(campaign.IsAudienceExpansionComplete);
        Assert.False(await summary.RefreshNextDispatchingAsync(now, CancellationToken.None));
        await summary.RefreshAsync(campaign.Id, now, CancellationToken.None);
        Assert.Equal(NotificationCampaignStatus.PreparingAudience, campaign.Status);
        Assert.Null(campaign.CompletedAtUtc);

        // Even an inconsistent legacy Dispatching row must not be selected
        // ahead of a genuinely completed audience and monopolize recovery.
        db.Entry(campaign).Property(item => item.Status).CurrentValue = NotificationCampaignStatus.Dispatching;
        var complete = DispatchingCampaign(now.AddSeconds(1));
        db.Campaigns.Add(complete);
        await db.SaveChangesAsync();
        Assert.True(await summary.RefreshNextDispatchingAsync(now, CancellationToken.None));
        Assert.Equal(NotificationCampaignStatus.Completed, complete.Status);
        Assert.Null(campaign.CompletedAtUtc);
    }

    [Theory]
    [InlineData(NotificationCampaignStatus.Cancelled)]
    [InlineData(NotificationCampaignStatus.Expired)]
    [InlineData(NotificationCampaignStatus.Failed)]
    public async Task Terminal_empty_campaign_is_never_reclassified_as_completed(NotificationCampaignStatus status)
    {
        var now = DateTimeOffset.UtcNow;
        await using var db = Context(new InMemoryDatabaseRoot(), $"terminal-empty-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        if (status == NotificationCampaignStatus.Cancelled) campaign.Cancel(now);
        else if (status == NotificationCampaignStatus.Expired) campaign.ExpireBeforeAudienceExpansion(now);
        else db.Entry(campaign).Property(item => item.Status).CurrentValue = status;
        db.Campaigns.Add(campaign);
        await db.SaveChangesAsync();
        var completed = campaign.CompletedAtUtc;
        var expired = campaign.ExpiredCount;
        var summary = new NotificationCampaignSummaryService(db);

        Assert.False(await summary.RefreshNextDispatchingAsync(now, CancellationToken.None));
        await summary.RefreshAsync(campaign.Id, now.AddHours(1), CancellationToken.None);
        Assert.Equal(status, campaign.Status);
        Assert.Equal(completed, campaign.CompletedAtUtc);
        Assert.Equal(expired, campaign.ExpiredCount);
    }

    [Fact]
    public async Task Empty_dispatching_campaign_past_expiry_terminalizes_as_expired()
    {
        var created = DateTimeOffset.UtcNow.AddDays(-2);
        await using var db = Context(new InMemoryDatabaseRoot(), $"empty-expired-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(created, lifetimeDays: 1);
        db.Campaigns.Add(campaign);
        await db.SaveChangesAsync();
        Assert.True(await new NotificationCampaignSummaryService(db).RefreshNextDispatchingAsync(
            created.AddDays(2), CancellationToken.None));
        Assert.Equal(NotificationCampaignStatus.Expired, campaign.Status);
        Assert.Equal(0, campaign.FcmAcceptedCount + campaign.FailedCount + campaign.SkippedCount);
    }

    [Theory]
    [InlineData(MobileNotificationTransportOutcomeKind.FcmAccepted, MobileNotificationTransportOutcomeKind.FcmAccepted, NotificationCampaignStatus.Completed, 0, 2, 0, 0)]
    [InlineData(MobileNotificationTransportOutcomeKind.PermanentFailure, MobileNotificationTransportOutcomeKind.PermanentFailure, NotificationCampaignStatus.CompletedWithFailures, 0, 0, 2, 0)]
    [InlineData(MobileNotificationTransportOutcomeKind.DeviceRevoked, MobileNotificationTransportOutcomeKind.DeviceRevoked, NotificationCampaignStatus.CompletedWithFailures, 0, 0, 0, 2)]
    [InlineData(MobileNotificationTransportOutcomeKind.FcmAccepted, MobileNotificationTransportOutcomeKind.PermanentFailure, NotificationCampaignStatus.CompletedWithFailures, 0, 1, 1, 0)]
    [InlineData(MobileNotificationTransportOutcomeKind.FcmAccepted, MobileNotificationTransportOutcomeKind.NoAvailableRoute, NotificationCampaignStatus.Dispatching, 1, 1, 0, 0)]
    public async Task Nonempty_summary_counts_and_pending_boundary_are_preserved(
        MobileNotificationTransportOutcomeKind first, MobileNotificationTransportOutcomeKind second,
        NotificationCampaignStatus expected, int pending, int accepted, int failed, int skipped)
    {
        var now = DateTimeOffset.UtcNow;
        await using var db = Context(new InMemoryDatabaseRoot(), $"summary-matrix-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipients = new[] { Recipient(campaign, now), Recipient(campaign, now) };
        db.Campaigns.Add(campaign);
        db.Recipients.AddRange(recipients);
        await db.SaveChangesAsync();
        foreach (var (recipient, outcome) in recipients.Zip(new[] { first, second }))
        {
            var claim = await PrepareClaimAsync(db, campaign, recipient, now, now.AddMinutes(2));
            await new NotificationDeliveryAttemptProcessor(db, new FixedTransport(outcome),
                Options.Create(OptionsForTests()), new MutableTimeProvider(now),
                NullLogger<NotificationDeliveryAttemptProcessor>.Instance).ProcessAsync(claim, CancellationToken.None);
        }

        await new NotificationCampaignSummaryService(db).RefreshAsync(campaign.Id, now, CancellationToken.None);
        Assert.Equal(expected, campaign.Status);
        Assert.Equal((pending, accepted, failed, skipped),
            (campaign.PendingCount, campaign.FcmAcceptedCount, campaign.FailedCount, campaign.SkippedCount));
    }

    [Theory]
    [InlineData(MobileNotificationTransportOutcomeKind.NoAvailableRoute, NotificationRecipientStatus.FailedPermanent)]
    [InlineData(MobileNotificationTransportOutcomeKind.FcmAccepted, NotificationRecipientStatus.FcmAccepted)]
    public async Task Legacy_retry_over_new_budget_gets_only_its_already_scheduled_final_chance(
        MobileNotificationTransportOutcomeKind finalOutcome, NotificationRecipientStatus expected)
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"legacy-budget-{Guid.NewGuid():N}";
        var time = new MutableTimeProvider(DateTimeOffset.UtcNow);
        await using (var oldRuntime = Context(root, name))
        {
            var campaign = DispatchingCampaign(time.GetUtcNow());
            var recipient = Recipient(campaign, time.GetUtcNow());
            oldRuntime.AddRange(campaign, recipient);
            await oldRuntime.SaveChangesAsync();
            for (var number = 0; number < 10; number++)
            {
                var claim = Assert.Single(await new NotificationWorkClaimer(oldRuntime).ClaimRecipientsAsync(
                    time.GetUtcNow(), time.GetUtcNow().AddMinutes(2), 10, CancellationToken.None));
                await new NotificationDeliveryAttemptProcessor(oldRuntime,
                    new FixedTransport(MobileNotificationTransportOutcomeKind.NoAvailableRoute),
                    Options.Create(OptionsForTests(20)), time,
                    NullLogger<NotificationDeliveryAttemptProcessor>.Instance).ProcessAsync(claim, CancellationToken.None);
                time.Set(recipient.NextAttemptAtUtc!.Value);
            }
        }

        using var provider = BuildWorkerProvider(root, name, time, new FixedTransport(finalOutcome));
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);
        await using var verify = Context(root, name);
        var result = await verify.Recipients.SingleAsync();
        Assert.Equal(expected, result.Status);
        Assert.Null(result.NextAttemptAtUtc);
        Assert.Equal(11, result.AttemptCount);
        Assert.Equal(11, await verify.DeliveryAttempts.CountAsync());
    }

    [Fact]
    public async Task Exhausted_attempt_projection_recovers_after_restart_without_another_send()
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"exhaustion-recovery-{Guid.NewGuid():N}";
        var now = DateTimeOffset.UtcNow;
        await using (var crashed = Context(root, name))
        {
            var campaign = DispatchingCampaign(now);
            var recipient = Recipient(campaign, now);
            crashed.AddRange(campaign, recipient);
            await crashed.SaveChangesAsync();
            var claim = await PrepareClaimAsync(crashed, campaign, recipient, now, now.AddMinutes(2));
            recipient.BeginAttempt(claim.LeaseId, claim.AttemptId, now);
            var attempt = await crashed.DeliveryAttempts.SingleAsync();
            attempt.BeginProviderInvocation(claim.LeaseId, now);
            NotificationDeliveryStateMachine.CompleteAttempt(attempt, claim.LeaseId,
                new MobileNotificationTransportOutcome(MobileNotificationTransportOutcomeKind.NoAvailableRoute, "no-active-route"), now);
            await crashed.SaveChangesAsync(); // crash before the recipient projection
        }

        await using var recovered = Context(root, name);
        var recovery = new NotificationDeliveryRecoveryProcessor(recovered,
            Options.Create(OptionsForTests(maximumAttempts: 1)), NullLogger<NotificationDeliveryRecoveryProcessor>.Instance);
        Assert.Single(await recovery.RecoverBatchAsync(now, 10, CancellationToken.None));
        Assert.Empty(await recovery.RecoverBatchAsync(now.AddMinutes(3), 10, CancellationToken.None));
        Assert.Equal(NotificationRecipientStatus.FailedPermanent, (await recovered.Recipients.SingleAsync()).Status);
        Assert.Empty(await new NotificationWorkClaimer(recovered).ClaimRecipientsAsync(now.AddMinutes(3),
            now.AddMinutes(5), 10, CancellationToken.None));
        Assert.Single(await recovered.DeliveryAttempts.ToArrayAsync());
    }

    [Fact]
    public async Task Audience_expansion_resumes_idempotently_after_reinstantiation()
    {
        var root = new InMemoryDatabaseRoot();
        var databaseName = $"audience-resume-{Guid.NewGuid():N}";
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        var devices = Enumerable.Range(0, 3)
            .Select(_ => new MobileBroadcastAudienceDevice(
                Guid.NewGuid(),
                $"installation-{Guid.NewGuid():N}",
                "android",
                "Test device"))
            .OrderBy(device => device.DeviceId)
            .ToArray();
        var audience = new PagedAudienceReader(devices);

        await using (var seed = Context(root, databaseName))
        {
            seed.Campaigns.Add(Campaign(now));
            await seed.SaveChangesAsync();
        }

        await ExpandOnePage(root, databaseName, audience, now, 2);
        await ExpandOnePage(root, databaseName, audience, now.AddSeconds(1), 2);

        await using var verify = Context(root, databaseName);
        var campaign = await verify.Campaigns.SingleAsync();
        var recipients = await verify.Recipients.ToArrayAsync();
        Assert.True(campaign.IsAudienceExpansionComplete);
        Assert.Equal(NotificationCampaignStatus.Dispatching, campaign.Status);
        Assert.Equal(3, recipients.Length);
        Assert.Equal(3, recipients.Select(recipient => recipient.MobileDeviceId).Distinct().Count());
    }

    [Theory]
    [InlineData(MobileNotificationTransportOutcomeKind.SignalRDispatched, NotificationRecipientStatus.SignalRDispatched, false)]
    [InlineData(MobileNotificationTransportOutcomeKind.FcmAccepted, NotificationRecipientStatus.FcmAccepted, false)]
    [InlineData(MobileNotificationTransportOutcomeKind.NoAvailableRoute, NotificationRecipientStatus.RetryScheduled, true)]
    [InlineData(MobileNotificationTransportOutcomeKind.TransientFailure, NotificationRecipientStatus.RetryScheduled, true)]
    [InlineData(MobileNotificationTransportOutcomeKind.PermanentFailure, NotificationRecipientStatus.FailedPermanent, false)]
    [InlineData(MobileNotificationTransportOutcomeKind.DeviceRevoked, NotificationRecipientStatus.SkippedRevoked, false)]
    [InlineData(MobileNotificationTransportOutcomeKind.Ambiguous, NotificationRecipientStatus.Ambiguous, false)]
    public async Task Typed_transport_outcomes_map_to_durable_recipient_state(
        MobileNotificationTransportOutcomeKind outcome,
        NotificationRecipientStatus expectedStatus,
        bool expectsRetry)
    {
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"outcome-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();

        var claim = await PrepareClaimAsync(
            db,
            campaign,
            recipient,
            now,
            now.AddMinutes(2));
        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            new FixedTransport(outcome),
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now),
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);

        await processor.ProcessAsync(claim, CancellationToken.None);

        Assert.Equal(expectedStatus, recipient.Status);
        Assert.Equal(expectsRetry, recipient.NextAttemptAtUtc.HasValue);
        Assert.Null(recipient.LeaseId);
    }

    [Fact]
    public async Task Retry_uses_bounded_exponential_backoff()
    {
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"retry-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var time = new MutableTimeProvider(now);
        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            new FixedTransport(MobileNotificationTransportOutcomeKind.TransientFailure),
            Options.Create(OptionsForTests(maximumAttempts: 20)),
            time,
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);

        for (var attempt = 0; attempt < 12; attempt++)
        {
            var claim = await PrepareClaimAsync(
                db,
                campaign,
                recipient,
                time.GetUtcNow(),
                time.GetUtcNow().AddMinutes(2));
            await processor.ProcessAsync(claim, CancellationToken.None);
            time.Set(recipient.NextAttemptAtUtc!.Value);
        }

        Assert.Equal(12, recipient.AttemptCount);
        Assert.True(recipient.NextAttemptAtUtc - recipient.LastAttemptAtUtc <= TimeSpan.FromMinutes(1));
    }

    [Fact]
    public async Task Expired_recipient_is_terminal_without_transport_dispatch()
    {
        var created = new DateTimeOffset(2026, 7, 1, 10, 0, 0, TimeSpan.Zero);
        var now = created.AddDays(29);
        await using var db = Context(new InMemoryDatabaseRoot(), $"expiry-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(created, lifetimeDays: 28);
        var recipient = Recipient(campaign, created);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var claim = await PrepareClaimAsync(
            db,
            campaign,
            recipient,
            created,
            now.AddMinutes(2));
        var transport = new RecordingTransport();

        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            transport,
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now),
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);
        await processor.ProcessAsync(claim, CancellationToken.None);

        Assert.Equal(NotificationRecipientStatus.Expired, recipient.Status);
        Assert.Equal(0, transport.Calls);
    }

    [Fact]
    public async Task Delivery_attempt_retains_originating_application_context()
    {
        var now = DateTimeOffset.UtcNow;
        await using var db = Context(
            new InMemoryDatabaseRoot(),
            $"application-context-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var claim = await PrepareClaimAsync(
            db,
            campaign,
            recipient,
            now,
            now.AddMinutes(2));
        var transport = new RecordingTransport();

        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            transport,
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now),
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);

        await processor.ProcessAsync(claim, CancellationToken.None);

        Assert.NotNull(transport.LastRequest);
        Assert.Equal(
            campaign.PlatformClientId,
            transport.LastRequest!.Application.ApplicationId);
        Assert.Equal(campaign.Id, transport.LastRequest.CampaignId);
    }

    [Fact]
    public async Task Stale_lease_is_recovered_but_live_lease_is_not_double_claimed()
    {
        var now = DateTimeOffset.UtcNow;
        await using var db = Context(new InMemoryDatabaseRoot(), $"stale-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var claimer = new NotificationWorkClaimer(db);

        var first = await claimer.ClaimRecipientsAsync(now, now.AddSeconds(30), 10, CancellationToken.None);
        var liveLeaseAttempt = await claimer.ClaimRecipientsAsync(now.AddSeconds(1), now.AddMinutes(1), 10, CancellationToken.None);
        var staleLeaseAttempt = await claimer.ClaimRecipientsAsync(now.AddSeconds(31), now.AddMinutes(2), 10, CancellationToken.None);

        Assert.Single(first);
        Assert.Empty(liveLeaseAttempt);
        Assert.Single(staleLeaseAttempt);
        Assert.Equal(first[0].Id, staleLeaseAttempt[0].Id);
        Assert.NotEqual(first[0].LeaseId, staleLeaseAttempt[0].LeaseId);
    }

    [Fact]
    public async Task Prepared_retry_claim_is_reused_after_restart_and_expired_lease()
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"prepared-retry-reclaim-{Guid.NewGuid():N}";
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        var time = new MutableTimeProvider(now);
        ClaimedNotificationWork crashedClaim;
        DateTimeOffset retryAt;

        await using (var crashed = Context(root, name))
        {
            var campaign = DispatchingCampaign(now);
            var recipient = Recipient(campaign, now);
            crashed.AddRange(campaign, recipient);
            await crashed.SaveChangesAsync();

            var firstClaim = Assert.Single(await new NotificationWorkClaimer(crashed).ClaimRecipientsAsync(
                now, now.AddMinutes(2), 10, CancellationToken.None));
            await new NotificationDeliveryAttemptProcessor(
                crashed,
                new FixedTransport(MobileNotificationTransportOutcomeKind.NoAvailableRoute),
                Options.Create(OptionsForTests()),
                time,
                NullLogger<NotificationDeliveryAttemptProcessor>.Instance)
                .ProcessAsync(firstClaim, CancellationToken.None);

            Assert.Equal(NotificationRecipientStatus.RetryScheduled, recipient.Status);
            Assert.Equal(1, recipient.AttemptCount);
            retryAt = recipient.NextAttemptAtUtc!.Value;
            time.Set(retryAt);

            crashedClaim = Assert.Single(await new NotificationWorkClaimer(crashed).ClaimRecipientsAsync(
                retryAt, retryAt.AddMinutes(2), 10, CancellationToken.None));
            Assert.Equal(2, crashedClaim.AttemptNumber);
            Assert.Equal(recipient.DeliveryKey, crashedClaim.DeliveryKey);
            Assert.Equal(2, await crashed.DeliveryAttempts.CountAsync());
            Assert.Equal(
                NotificationDeliveryAttemptStatus.Prepared,
                (await crashed.DeliveryAttempts.SingleAsync(attempt => attempt.Id == crashedClaim.AttemptId)).Status);
        }

        var reclaimedAt = retryAt.AddMinutes(3);
        time.Set(reclaimedAt);
        await using var recovered = Context(root, name);
        var reclaimedClaim = Assert.Single(await new NotificationWorkClaimer(recovered).ClaimRecipientsAsync(
            reclaimedAt, reclaimedAt.AddMinutes(2), 10, CancellationToken.None));

        Assert.Equal(crashedClaim.AttemptId, reclaimedClaim.AttemptId);
        Assert.Equal(crashedClaim.AttemptNumber, reclaimedClaim.AttemptNumber);
        Assert.Equal(crashedClaim.DeliveryKey, reclaimedClaim.DeliveryKey);
        Assert.NotEqual(crashedClaim.LeaseId, reclaimedClaim.LeaseId);
        Assert.Equal(2, await recovered.DeliveryAttempts.CountAsync());

        var transport = new RecordingTransport();
        var processor = new NotificationDeliveryAttemptProcessor(
            recovered,
            transport,
            Options.Create(OptionsForTests()),
            time,
            NullLogger<NotificationDeliveryAttemptProcessor>.Instance);

        var staleResult = await processor.ProcessAsync(crashedClaim, CancellationToken.None);
        Assert.False(staleResult.Processed);
        Assert.Equal(0, transport.Calls);

        var reclaimedResult = await processor.ProcessAsync(reclaimedClaim, CancellationToken.None);
        Assert.True(reclaimedResult.Processed);
        Assert.Equal(MobileNotificationTransportOutcomeKind.SignalRDispatched, reclaimedResult.Outcome);
        Assert.Equal(1, transport.Calls);
        Assert.Equal(reclaimedClaim.AttemptId, transport.LastRequest!.DeliveryAttemptId);

        var delivered = await recovered.Recipients.SingleAsync();
        Assert.Equal(NotificationRecipientStatus.SignalRDispatched, delivered.Status);
        Assert.Equal(2, delivered.AttemptCount);
        Assert.Empty(await new NotificationWorkClaimer(recovered).ClaimRecipientsAsync(
            reclaimedAt.AddDays(1), reclaimedAt.AddDays(1).AddMinutes(2), 10, CancellationToken.None));
        Assert.Equal(NotificationRecipientStatus.SignalRDispatched, delivered.Status);
        Assert.Equal(2, await recovered.DeliveryAttempts.CountAsync());
    }

    [Fact]
    public async Task Concurrent_claimers_cannot_claim_same_recipient()
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"concurrent-{Guid.NewGuid():N}";
        var now = DateTimeOffset.UtcNow;
        await using (var seed = Context(root, name))
        {
            var campaign = DispatchingCampaign(now);
            seed.AddRange(campaign, Recipient(campaign, now));
            await seed.SaveChangesAsync();
        }

        await using var firstDb = Context(root, name);
        await using var secondDb = Context(root, name);
        var results = await Task.WhenAll(
            new NotificationWorkClaimer(firstDb).ClaimRecipientsAsync(
                now,
                now.AddMinutes(2),
                10,
                CancellationToken.None),
            new NotificationWorkClaimer(secondDb).ClaimRecipientsAsync(
                now,
                now.AddMinutes(2),
                10,
                CancellationToken.None));

        Assert.Equal(1, results.Sum(result => result.Count));
    }

    [Fact]
    public async Task Reinstantiated_worker_resumes_retry_scheduled_work()
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"worker-restart-{Guid.NewGuid():N}";
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        var time = new MutableTimeProvider(now);
        var transport = new MutableTransport(MobileNotificationTransportOutcomeKind.NoAvailableRoute);
        using var provider = BuildWorkerProvider(root, name, time, transport);

        await using (var seed = Context(root, name))
        {
            var campaign = DispatchingCampaign(now);
            seed.AddRange(campaign, Recipient(campaign, now));
            await seed.SaveChangesAsync();
        }

        var firstWorker = Worker(provider, time);
        await firstWorker.RunIterationAsync(CancellationToken.None);

        time.Set(now.AddSeconds(2));
        transport.Outcome = MobileNotificationTransportOutcomeKind.SignalRDispatched;
        var restartedWorker = Worker(provider, time);
        await restartedWorker.RunIterationAsync(CancellationToken.None);

        await using var verify = Context(root, name);
        var recipient = await verify.Recipients.SingleAsync();
        Assert.Equal(NotificationRecipientStatus.SignalRDispatched, recipient.Status);
        Assert.Equal(2, recipient.AttemptCount);
    }

    [Fact]
    public async Task One_recipient_failure_does_not_abort_other_recipient()
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"failure-isolation-{Guid.NewGuid():N}";
        var now = DateTimeOffset.UtcNow;
        var time = new MutableTimeProvider(now);
        Guid failingDevice;

        await using (var seed = Context(root, name))
        {
            var campaign = DispatchingCampaign(now);
            var failing = Recipient(campaign, now);
            failingDevice = failing.MobileDeviceId;
            seed.AddRange(campaign, failing, Recipient(campaign, now));
            await seed.SaveChangesAsync();
        }

        using var provider = BuildWorkerProvider(
            root,
            name,
            time,
            new SelectiveFailureTransport(failingDevice));
        await Worker(provider, time).RunIterationAsync(CancellationToken.None);

        await using var verify = Context(root, name);
        var recipients = await verify.Recipients.ToArrayAsync();
        Assert.Contains(recipients, recipient =>
            recipient.Status == NotificationRecipientStatus.SignalRDispatched);
        Assert.Contains(recipients, recipient =>
            recipient.Status == NotificationRecipientStatus.Sending
            && recipient.LeaseId.HasValue);
    }

    private static async Task ExpandOnePage(
        InMemoryDatabaseRoot root,
        string name,
        IMobileBroadcastAudienceReader audience,
        DateTimeOffset now,
        int pageSize)
    {
        await using var db = Context(root, name);
        var claim = await new NotificationWorkClaimer(db).ClaimAudienceAsync(
            now,
            now.AddMinutes(2),
            CancellationToken.None);
        Assert.NotNull(claim);
        await new NotificationAudienceExpander(db, audience).ExpandClaimedPageAsync(
            claim!,
            pageSize,
            now,
            CancellationToken.None);
    }

    private static ServiceProvider BuildWorkerProvider(
        InMemoryDatabaseRoot root,
        string name,
        MutableTimeProvider time,
        IMobileNotificationTransport transport)
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddSingleton<TimeProvider>(time);
        services.AddSingleton(Options.Create(OptionsForTests()));
        services.AddSingleton(transport);
        services.AddSingleton<IMobileBroadcastAudienceReader>(new PagedAudienceReader([]));
        services.AddDbContext<NotificationsDbContext>(builder =>
            builder.UseInMemoryDatabase(name, root));
        services.AddScoped<NotificationWorkClaimer>();
        services.AddScoped<NotificationAudienceExpander>();
        services.AddScoped<NotificationDeliveryAttemptProcessor>();
        services.AddScoped<NotificationDeliveryRecoveryProcessor>();
        services.AddScoped<NotificationCampaignSummaryService>();
        services.AddScoped<NotificationExpiryProcessor>();
        return services.BuildServiceProvider();
    }

    private static NotificationCampaignBackgroundService Worker(
        IServiceProvider provider,
        TimeProvider time)
    {
        return new NotificationCampaignBackgroundService(
            provider.GetRequiredService<IServiceScopeFactory>(),
            Options.Create(OptionsForTests()),
            time,
            NullLogger<NotificationCampaignBackgroundService>.Instance);
    }

    private static NotificationCampaignOptions OptionsForTests(int maximumAttempts = 8) => new()
    {
        DefaultCampaignLifetimeDays = 28,
        MinimumCampaignLifetimeDays = 1,
        MaximumCampaignLifetimeDays = 28,
        Worker = new NotificationWorkerOptions
        {
            BatchSize = 100,
            PollIntervalSeconds = 1,
            LeaseSeconds = 120,
            MaxParallelDeliveries = 4
        },
        Retry = new NotificationRetryOptions
        {
            MaximumAttempts = maximumAttempts,
            InitialDelaySeconds = 1,
            MaximumDelayMinutes = 1
        }
    };

    private static NotificationCampaign Campaign(
        DateTimeOffset now,
        int lifetimeDays = 28)
    {
        return NotificationCampaign.Create(
            Guid.NewGuid(),
            "app-key",
            "Application",
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            "عنوان",
            "Title",
            "نص",
            "Body",
            "general",
            NotificationPriority.Normal,
            Guid.NewGuid().ToString(),
            new string('A', 64),
            Guid.NewGuid(),
            "Administrator",
            now,
            now.AddDays(lifetimeDays),
            2,
            3,
            2);
    }

    private static NotificationCampaign DispatchingCampaign(
        DateTimeOffset now,
        int lifetimeDays = 28)
    {
        var campaign = Campaign(now, lifetimeDays);
        campaign.ClaimAudience(Guid.NewGuid(), now.AddMinutes(2), now);
        campaign.AdvanceAudience(null, 0, true);
        return campaign;
    }

    private static NotificationRecipient Recipient(
        NotificationCampaign campaign,
        DateTimeOffset now)
    {
        return NotificationRecipient.Create(
            campaign.PlatformClientId,
            campaign.Id,
            Guid.NewGuid(),
            $"installation-{Guid.NewGuid():N}",
            "android",
            "Test device",
            now,
            campaign.ExpiresAtUtc);
    }

    private static async Task<ClaimedNotificationWork> PrepareClaimAsync(
        NotificationsDbContext db,
        NotificationCampaign campaign,
        NotificationRecipient recipient,
        DateTimeOffset now,
        DateTimeOffset leaseExpiresAtUtc)
    {
        var leaseId = Guid.NewGuid();
        var attempt = NotificationDeliveryAttempt.Create(
            Guid.NewGuid(),
            recipient.Id,
            campaign.PlatformClientId,
            campaign.Id,
            recipient.MobileDeviceId,
            recipient.DeliveryKey,
            recipient.AttemptCount + 1,
            leaseId,
            now);
        db.DeliveryAttempts.Add(attempt);
        recipient.Claim(leaseId, leaseExpiresAtUtc, attempt.Id);
        await db.SaveChangesAsync();
        return new ClaimedNotificationWork(
            recipient.Id,
            leaseId,
            attempt.Id,
            recipient.DeliveryKey,
            attempt.AttemptNumber);
    }

    private static NotificationsDbContext Context(
        InMemoryDatabaseRoot root,
        string name)
    {
        var options = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase(name, root)
            .Options;
        return new NotificationsDbContext(options);
    }

    private sealed class FixedTransport(MobileNotificationTransportOutcomeKind outcome)
        : IMobileNotificationTransport
    {
        public Task<MobileNotificationTransportOutcome> DispatchAsync(MobileNotificationTransportRequest request, CancellationToken cancellationToken) =>
            Task.FromResult(new MobileNotificationTransportOutcome(outcome, outcome.ToString()));
    }

    private sealed class RecordingTransport : IMobileNotificationTransport
    {
        public int Calls { get; private set; }
        public MobileNotificationTransportRequest? LastRequest
        {
            get;
            private set;
        }

        public Task<MobileNotificationTransportOutcome> DispatchAsync(MobileNotificationTransportRequest request, CancellationToken cancellationToken)
        {
            Calls++;
            LastRequest = request;
            return Task.FromResult(new MobileNotificationTransportOutcome(MobileNotificationTransportOutcomeKind.SignalRDispatched));
        }
    }

    private sealed class MutableTransport(MobileNotificationTransportOutcomeKind outcome)
        : IMobileNotificationTransport
    {
        public MobileNotificationTransportOutcomeKind Outcome { get; set; } = outcome;
        public Task<MobileNotificationTransportOutcome> DispatchAsync(MobileNotificationTransportRequest request, CancellationToken cancellationToken) =>
            Task.FromResult(new MobileNotificationTransportOutcome(Outcome));
    }

    private sealed class SelectiveFailureTransport(Guid failingDevice)
        : IMobileNotificationTransport
    {
        public Task<MobileNotificationTransportOutcome> DispatchAsync(MobileNotificationTransportRequest request, CancellationToken cancellationToken)
        {
            if (request.MobileDeviceId == failingDevice)
            {
                throw new InvalidOperationException("Synthetic isolated failure.");
            }

            return Task.FromResult(new MobileNotificationTransportOutcome(
                MobileNotificationTransportOutcomeKind.SignalRDispatched));
        }
    }

    private sealed class PagedAudienceReader(
        IReadOnlyList<MobileBroadcastAudienceDevice> devices,
        bool fail = false)
        : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, CancellationToken cancellationToken) =>
            Task.FromResult(new MobileBroadcastAudiencePreview(devices.Count, devices.Count, 0));

        public Task<MobileBroadcastAudiencePage> ReadPageAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, Guid? afterDeviceId, int pageSize, CancellationToken cancellationToken)
        {
            if (fail) throw new InvalidOperationException("Synthetic audience read failure");
            var page = devices
                .Where(device => !afterDeviceId.HasValue || device.DeviceId.CompareTo(afterDeviceId.Value) > 0)
                .OrderBy(device => device.DeviceId)
                .Take(pageSize + 1)
                .ToArray();
            return Task.FromResult(new MobileBroadcastAudiencePage(
                page.Take(pageSize).ToArray(),
                page.Length > pageSize));
        }

        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(NotificationApplicationContext application, Guid deviceId, CancellationToken cancellationToken) =>
            Task.FromResult(new MobileBroadcastDeviceState(true, false));
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        private DateTimeOffset _now = now;
        public override DateTimeOffset GetUtcNow() => _now;
        public void Set(DateTimeOffset value) => _now = value;
    }

    private sealed class RecordingLogger<T> : ILogger<T>
    {
        public List<string> Messages { get; } = [];
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(LogLevel logLevel) => true;
        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter) => Messages.Add(formatter(state, exception));
    }
}
