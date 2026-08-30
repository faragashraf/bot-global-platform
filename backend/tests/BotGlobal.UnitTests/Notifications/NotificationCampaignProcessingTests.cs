using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignProcessingTests
{
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
    public async Task Typed_transport_outcomes_map_to_durable_recipient_state(
        MobileNotificationTransportOutcomeKind outcome,
        NotificationRecipientStatus expectedStatus,
        bool expectsRetry)
    {
        var now = new DateTimeOffset(2026, 8, 21, 10, 0, 0, TimeSpan.Zero);
        await using var db = Context(new InMemoryDatabaseRoot(), $"outcome-{Guid.NewGuid():N}");
        var campaign = DispatchingCampaign(now);
        var recipient = Recipient(campaign, now);
        recipient.Claim(Guid.NewGuid(), now.AddMinutes(2));
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();

        var claim = new ClaimedNotificationWork(recipient.Id, recipient.LeaseId!.Value);
        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            new FixedTransport(outcome),
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now));

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
            Options.Create(OptionsForTests()),
            time);

        for (var attempt = 0; attempt < 12; attempt++)
        {
            recipient.Claim(Guid.NewGuid(), time.GetUtcNow().AddMinutes(2));
            await db.SaveChangesAsync();
            await processor.ProcessAsync(
                new ClaimedNotificationWork(recipient.Id, recipient.LeaseId!.Value),
                CancellationToken.None);
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
        recipient.Claim(Guid.NewGuid(), now.AddMinutes(2));
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var transport = new RecordingTransport();

        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            transport,
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now));
        await processor.ProcessAsync(
            new ClaimedNotificationWork(recipient.Id, recipient.LeaseId!.Value),
            CancellationToken.None);

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
        recipient.Claim(Guid.NewGuid(), now.AddMinutes(2));
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        var transport = new RecordingTransport();

        var processor = new NotificationDeliveryAttemptProcessor(
            db,
            transport,
            Options.Create(OptionsForTests()),
            new MutableTimeProvider(now));

        await processor.ProcessAsync(
            new ClaimedNotificationWork(
                recipient.Id,
                recipient.LeaseId!.Value),
            CancellationToken.None);

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
            recipient.Status == NotificationRecipientStatus.Pending
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

    private static NotificationCampaignOptions OptionsForTests() => new()
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
            campaign.Id,
            Guid.NewGuid(),
            $"installation-{Guid.NewGuid():N}",
            "android",
            "Test device",
            now,
            campaign.ExpiresAtUtc);
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
        public MobileNotificationTransportRequest? LastRequest {
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
        IReadOnlyList<MobileBroadcastAudienceDevice> devices)
        : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, CancellationToken cancellationToken) =>
            Task.FromResult(new MobileBroadcastAudiencePreview(devices.Count, devices.Count, 0));

        public Task<MobileBroadcastAudiencePage> ReadPageAsync(NotificationApplicationContext application, DateTimeOffset audienceAsOfUtc, Guid? afterDeviceId, int pageSize, CancellationToken cancellationToken)
        {
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
}
