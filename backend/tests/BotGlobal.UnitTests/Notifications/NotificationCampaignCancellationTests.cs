using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignCancellationTests
{
    [Fact]
    public async Task Queued_campaign_cancellation_accounts_for_the_unexpanded_audience()
    {
        var fixture = await SeedQueuedAsync(audienceDeviceCount: 3);

        var response = await CancelAsync(
            fixture,
            fixture.ApplicationId,
            fixture.Now);

        await using var db = fixture.Context();
        Assert.Equal("Cancelled", response!.Status);
        Assert.Equal(0, response.PendingCount);
        Assert.Equal(3, response.SkippedCount);
        Assert.Empty(await db.Recipients.ToArrayAsync());
    }

    [Fact]
    public async Task Cancelled_before_claim_is_terminal_without_transport()
    {
        var fixture = await SeedAsync();
        await CancelAsync(fixture, fixture.ApplicationId, fixture.Now);

        await using var db = fixture.Context();
        var claims = await new NotificationWorkClaimer(db)
            .ClaimRecipientsAsync(
                fixture.Now,
                fixture.Now.AddMinutes(2),
                10,
                CancellationToken.None);
        var campaign = await db.Campaigns.SingleAsync();
        var recipient = await db.Recipients.SingleAsync();

        Assert.Empty(claims);
        Assert.Equal(NotificationCampaignStatus.Cancelled, campaign.Status);
        Assert.Equal(NotificationRecipientStatus.Cancelled, recipient.Status);
        Assert.Equal(0, campaign.PendingCount);
        Assert.Equal(1, campaign.SkippedCount);
    }

    [Fact]
    public async Task Cancelled_after_claim_before_provider_invocation_never_sends()
    {
        var fixture = await SeedAsync();
        var claim = await ClaimAsync(fixture, fixture.Now);
        await CancelAsync(fixture, fixture.ApplicationId, fixture.Now.AddSeconds(1));
        var transport = new RecordingTransport();

        var result = await ProcessAsync(
            fixture,
            claim,
            transport,
            fixture.Now.AddSeconds(2));

        await using var db = fixture.Context();
        Assert.False(result.Processed);
        Assert.Equal(0, transport.Calls);
        Assert.Equal(
            NotificationRecipientStatus.Cancelled,
            (await db.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Cancelled,
            (await db.DeliveryAttempts.SingleAsync()).Status);
    }

    [Fact]
    public async Task Worker_observes_authoritative_campaign_cancellation_before_provider_invocation()
    {
        var fixture = await SeedAsync();
        var claim = await ClaimAsync(fixture, fixture.Now);
        await using (var cancelCampaign = fixture.Context())
        {
            var campaign = await cancelCampaign.Campaigns.SingleAsync();
            campaign.Cancel(fixture.Now.AddSeconds(1));
            await cancelCampaign.SaveChangesAsync();
        }

        var transport = new RecordingTransport();
        var result = await ProcessAsync(
            fixture,
            claim,
            transport,
            fixture.Now.AddSeconds(2));

        await using var db = fixture.Context();
        Assert.True(result.Processed);
        Assert.Equal(0, transport.Calls);
        Assert.Equal(
            NotificationRecipientStatus.Cancelled,
            (await db.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Cancelled,
            (await db.DeliveryAttempts.SingleAsync()).Status);
    }

    [Fact]
    public async Task Cancellation_rejects_both_stale_and_current_lease_owners()
    {
        var fixture = await SeedAsync();
        var stale = await ClaimAsync(
            fixture,
            fixture.Now,
            fixture.Now.AddSeconds(30));
        var current = await ClaimAsync(
            fixture,
            fixture.Now.AddSeconds(31),
            fixture.Now.AddMinutes(2));
        await CancelAsync(fixture, fixture.ApplicationId, fixture.Now.AddSeconds(32));
        var transport = new RecordingTransport();

        var staleResult = await ProcessAsync(
            fixture,
            stale,
            transport,
            fixture.Now.AddSeconds(33));
        var currentResult = await ProcessAsync(
            fixture,
            current,
            transport,
            fixture.Now.AddSeconds(33));

        Assert.False(staleResult.Processed);
        Assert.False(currentResult.Processed);
        Assert.Equal(0, transport.Calls);
        Assert.Equal(stale.AttemptId, current.AttemptId);
        Assert.NotEqual(stale.LeaseId, current.LeaseId);
    }

    [Fact]
    public async Task Provider_acceptance_survives_later_campaign_cancellation()
    {
        var fixture = await SeedAsync();
        var claim = await ClaimAsync(fixture, fixture.Now);
        var transport = new RecordingTransport(
            MobileNotificationTransportOutcomeKind.FcmAccepted,
            "projects/test/messages/already-accepted");
        await ProcessAsync(
            fixture,
            claim,
            transport,
            fixture.Now.AddSeconds(1));

        await CancelAsync(fixture, fixture.ApplicationId, fixture.Now.AddSeconds(2));

        await using var db = fixture.Context();
        var campaign = await db.Campaigns.SingleAsync();
        var recipient = await db.Recipients.SingleAsync();
        var attempt = await db.DeliveryAttempts.SingleAsync();
        var laterClaims = await new NotificationWorkClaimer(db)
            .ClaimRecipientsAsync(
                fixture.Now.AddMinutes(1),
                fixture.Now.AddMinutes(3),
                10,
                CancellationToken.None);

        Assert.Equal(1, transport.Calls);
        Assert.Empty(laterClaims);
        Assert.Equal(NotificationCampaignStatus.Cancelled, campaign.Status);
        Assert.Equal(NotificationRecipientStatus.FcmAccepted, recipient.Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.FcmAccepted,
            attempt.Status);
        Assert.Equal(
            "projects/test/messages/already-accepted",
            attempt.ProviderMessageId);
        Assert.Equal(1, campaign.FcmAcceptedCount);
    }

    [Fact]
    public async Task Expired_delivery_and_campaign_remain_expired_when_cancel_is_requested()
    {
        var fixture = await SeedAsync(lifetime: TimeSpan.FromDays(1));
        var afterExpiry = fixture.Now.AddDays(2);
        var claim = await ClaimAsync(
            fixture,
            fixture.Now,
            afterExpiry.AddMinutes(1));
        var transport = new RecordingTransport();
        await ProcessAsync(fixture, claim, transport, afterExpiry);

        await using (var summarize = fixture.Context())
        {
            await new NotificationCampaignSummaryService(summarize)
                .RefreshAsync(
                    fixture.CampaignId,
                    afterExpiry,
                    CancellationToken.None);
        }

        var response = await CancelAsync(
            fixture,
            fixture.ApplicationId,
            afterExpiry.AddSeconds(1));

        await using var db = fixture.Context();
        Assert.Equal(0, transport.Calls);
        Assert.Equal("Expired", response!.Status);
        Assert.Equal(
            NotificationCampaignStatus.Expired,
            (await db.Campaigns.SingleAsync()).Status);
        Assert.Equal(
            NotificationRecipientStatus.Expired,
            (await db.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Expired,
            (await db.DeliveryAttempts.SingleAsync()).Status);
    }

    [Fact]
    public async Task Application_scoped_cancellation_cannot_affect_another_application()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var fixture = await SeedAsync(applicationId: appB);

        var response = await CancelAsync(
            fixture,
            appA,
            fixture.Now,
            appA,
            appB);

        await using var db = fixture.Context();
        Assert.Null(response);
        Assert.Equal(
            NotificationCampaignStatus.Dispatching,
            (await db.Campaigns.SingleAsync()).Status);
        Assert.Equal(
            NotificationRecipientStatus.Pending,
            (await db.Recipients.SingleAsync()).Status);
    }

    private static async Task<Fixture> SeedAsync(
        Guid? applicationId = null,
        TimeSpan? lifetime = null)
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"campaign-cancellation-{Guid.NewGuid():N}";
        var now = new DateTimeOffset(
            2026,
            8,
            30,
            10,
            0,
            0,
            TimeSpan.Zero);
        var appId = applicationId ?? Guid.NewGuid();
        var campaign = NotificationCampaign.Create(
            appId,
            "test-app",
            "Test application",
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            "عنوان",
            "Title",
            "نص",
            "Body",
            "general",
            NotificationPriority.Normal,
            Guid.NewGuid().ToString("N"),
            new string('A', 64),
            Guid.NewGuid(),
            "Administrator",
            now,
            now.Add(lifetime ?? TimeSpan.FromDays(7)),
            1,
            1,
            1);
        campaign.ClaimAudience(Guid.NewGuid(), now.AddMinutes(2), now);
        campaign.AdvanceAudience(null, 1, true);
        var recipient = NotificationRecipient.Create(
            appId,
            campaign.Id,
            Guid.NewGuid(),
            $"installation-{Guid.NewGuid():N}",
            "android",
            "Test device",
            now,
            campaign.ExpiresAtUtc);

        var fixture = new Fixture(
            root,
            name,
            now,
            appId,
            campaign.Id);
        await using var db = fixture.Context();
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        return fixture;
    }

    private static async Task<Fixture> SeedQueuedAsync(
        int audienceDeviceCount)
    {
        var root = new InMemoryDatabaseRoot();
        var name = $"queued-campaign-cancellation-{Guid.NewGuid():N}";
        var now = new DateTimeOffset(
            2026,
            8,
            30,
            10,
            0,
            0,
            TimeSpan.Zero);
        var applicationId = Guid.NewGuid();
        var campaign = NotificationCampaign.Create(
            applicationId,
            "test-app",
            "Test application",
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            "عنوان",
            "Title",
            "نص",
            "Body",
            "general",
            NotificationPriority.Normal,
            Guid.NewGuid().ToString("N"),
            new string('A', 64),
            Guid.NewGuid(),
            "Administrator",
            now,
            now.AddDays(7),
            audienceDeviceCount,
            audienceDeviceCount,
            audienceDeviceCount);
        var fixture = new Fixture(
            root,
            name,
            now,
            applicationId,
            campaign.Id);
        await using var db = fixture.Context();
        db.Campaigns.Add(campaign);
        await db.SaveChangesAsync();
        return fixture;
    }

    private static async Task<ClaimedNotificationWork> ClaimAsync(
        Fixture fixture,
        DateTimeOffset now,
        DateTimeOffset? leaseExpiresAtUtc = null)
    {
        await using var db = fixture.Context();
        var claims = await new NotificationWorkClaimer(db)
            .ClaimRecipientsAsync(
                now,
                leaseExpiresAtUtc ?? now.AddMinutes(2),
                10,
                CancellationToken.None);
        return Assert.Single(claims);
    }

    private static async Task<NotificationCampaignSummaryResponse?> CancelAsync(
        Fixture fixture,
        Guid scopeApplicationId,
        DateTimeOffset now,
        params Guid[] recognizedApplicationIds)
    {
        Guid[] recognized = recognizedApplicationIds.Length == 0
            ? [scopeApplicationId]
            : recognizedApplicationIds;
        await using var db = fixture.Context();
        return await new NotificationCampaignService(
                db,
                new DescriptorReader(recognized),
                new EmptyAudienceReader(),
                Options.Create(new NotificationCampaignOptions()),
                new FixedTimeProvider(now))
            .CancelAsync(
                ApplicationAdministrationScope.ForApplication(
                    scopeApplicationId),
                fixture.CampaignId,
                CancellationToken.None);
    }

    private static async Task<NotificationAttemptResult> ProcessAsync(
        Fixture fixture,
        ClaimedNotificationWork claim,
        IMobileNotificationTransport transport,
        DateTimeOffset now)
    {
        await using var db = fixture.Context();
        return await new NotificationDeliveryAttemptProcessor(
                db,
                transport,
                Options.Create(OptionsForTests()),
                new FixedTimeProvider(now),
                NullLogger<NotificationDeliveryAttemptProcessor>.Instance)
            .ProcessAsync(claim, CancellationToken.None);
    }

    private static NotificationCampaignOptions OptionsForTests() => new()
    {
        Retry = new NotificationRetryOptions
        {
            InitialDelaySeconds = 1,
            MaximumDelayMinutes = 1
        }
    };

    private sealed record Fixture(
        InMemoryDatabaseRoot Root,
        string DatabaseName,
        DateTimeOffset Now,
        Guid ApplicationId,
        Guid CampaignId)
    {
        public NotificationsDbContext Context()
        {
            var options = new DbContextOptionsBuilder<NotificationsDbContext>()
                .UseInMemoryDatabase(DatabaseName, Root)
                .Options;
            return new NotificationsDbContext(options);
        }
    }

    private sealed class DescriptorReader(params Guid[] applicationIds)
        : IPlatformClientDescriptorReader
    {
        public Task<PlatformClientDescriptor?> FindAsync(
            Guid platformClientId,
            CancellationToken cancellationToken)
        {
            var descriptor = applicationIds.Contains(platformClientId)
                ? new PlatformClientDescriptor(
                    platformClientId,
                    $"app-{platformClientId:N}",
                    "Application",
                    true)
                : null;
            return Task.FromResult(descriptor);
        }
    }

    private sealed class EmptyAudienceReader : IMobileBroadcastAudienceReader
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(
            NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<MobileBroadcastAudiencePage> ReadPageAsync(
            NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc,
            Guid? afterDeviceId,
            int pageSize,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(
            NotificationApplicationContext application,
            Guid deviceId,
            CancellationToken cancellationToken) =>
            throw new NotSupportedException();
    }

    private sealed class RecordingTransport(
        MobileNotificationTransportOutcomeKind outcome =
            MobileNotificationTransportOutcomeKind.SignalRDispatched,
        string? providerMessageId = null)
        : IMobileNotificationTransport
    {
        public int Calls { get; private set; }

        public Task<MobileNotificationTransportOutcome> DispatchAsync(
            MobileNotificationTransportRequest request,
            CancellationToken cancellationToken)
        {
            Calls++;
            return Task.FromResult(new MobileNotificationTransportOutcome(
                outcome,
                ProviderMessageId: providerMessageId,
                Transport: outcome
                    == MobileNotificationTransportOutcomeKind.FcmAccepted
                    ? "Fcm"
                    : "SignalR"));
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
