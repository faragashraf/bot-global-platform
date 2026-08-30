using BotGlobal.Contracts.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Domain;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.EntityFrameworkCore.Storage;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationDeliveryDurabilityTests
{
    [Fact]
    public async Task Stable_delivery_and_attempt_identity_survive_pre_send_lease_recovery()
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        var seeded = await SeedAsync(root, name, now);

        var first = await ClaimSingleAsync(root, name, now, now.AddSeconds(30));
        var recovered = await ClaimSingleAsync(
            root,
            name,
            now.AddSeconds(31),
            now.AddMinutes(2));

        Assert.Equal(first.Id, recovered.Id);
        Assert.Equal(first.AttemptId, recovered.AttemptId);
        Assert.Equal(first.DeliveryKey, recovered.DeliveryKey);
        Assert.Equal(first.AttemptNumber, recovered.AttemptNumber);
        Assert.NotEqual(first.LeaseId, recovered.LeaseId);
        Assert.Equal(seeded.DeliveryKey, recovered.DeliveryKey);
    }

    [Fact]
    public async Task Delivery_key_is_application_campaign_and_device_scoped()
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var a = await SeedAsync(root, name, now, appA);
        var b = await SeedAsync(root, name, now.AddSeconds(1), appB);

        Assert.NotEqual(a.DeliveryKey, b.DeliveryKey);
        Assert.StartsWith($"{appA:N}:", a.DeliveryKey);
        Assert.StartsWith($"{appB:N}:", b.DeliveryKey);

        var claim = await ClaimSingleAsync(root, name, now, now.AddMinutes(2));
        await using var db = Context(root, name);
        var attempt = await db.DeliveryAttempts.SingleAsync(candidate =>
            candidate.Id == claim.AttemptId);
        Assert.Equal(appA, attempt.ApplicationId);
        Assert.Equal(a.CampaignId, attempt.CampaignId);
        Assert.Equal(a.RecipientId, attempt.NotificationRecipientId);
    }

    [Fact]
    public async Task Concurrent_claimers_create_one_attempt_and_one_owner()
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        await SeedAsync(root, name, now);

        await using var firstDb = Context(root, name);
        await using var secondDb = Context(root, name);
        var claims = await Task.WhenAll(
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

        Assert.Equal(1, claims.Sum(result => result.Count));
        await using var verify = Context(root, name);
        Assert.Equal(1, await verify.DeliveryAttempts.CountAsync());
    }

    [Fact]
    public async Task Retryable_provider_rejection_creates_bounded_retry()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = FixedTransport(
            MobileNotificationTransportOutcomeKind.TransientFailure,
            "fcm-unavailable",
            transport: "Fcm");

        await ProcessAsync(fixture, transport);

        await using var verify = fixture.Context();
        var recipient = await verify.Recipients.SingleAsync();
        var attempt = await verify.DeliveryAttempts.SingleAsync();
        Assert.Equal(NotificationRecipientStatus.RetryScheduled, recipient.Status);
        Assert.Equal(NotificationDeliveryAttemptStatus.RetryableFailure, attempt.Status);
        Assert.Equal(fixture.Now.AddSeconds(1), recipient.NextAttemptAtUtc);
    }

    [Fact]
    public async Task Invalid_device_token_is_permanent_and_never_reclaimed()
    {
        var fixture = await CreateClaimedFixtureAsync();
        await ProcessAsync(
            fixture,
            FixedTransport(
                MobileNotificationTransportOutcomeKind.PermanentFailure,
                "fcm-unregistered",
                transport: "Fcm"));

        await using var verify = fixture.Context();
        var recipient = await verify.Recipients.SingleAsync();
        Assert.Equal(NotificationRecipientStatus.FailedPermanent, recipient.Status);
        Assert.Null(recipient.NextAttemptAtUtc);

        var retry = await new NotificationWorkClaimer(verify)
            .ClaimRecipientsAsync(
                fixture.Now.AddHours(1),
                fixture.Now.AddHours(1).AddMinutes(2),
                10,
                CancellationToken.None);
        Assert.Empty(retry);
    }

    [Fact]
    public async Task Provider_acceptance_persists_safe_message_metadata_and_stable_notification_id()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = new RecordingTransport(new MobileNotificationTransportOutcome(
            MobileNotificationTransportOutcomeKind.FcmAccepted,
            ProviderMessageId: "projects/test/messages/accepted-1",
            Transport: "Fcm"));

        await ProcessAsync(fixture, transport);

        await using var verify = fixture.Context();
        var recipient = await verify.Recipients.SingleAsync();
        var attempt = await verify.DeliveryAttempts.SingleAsync();
        Assert.Equal(NotificationRecipientStatus.FcmAccepted, recipient.Status);
        Assert.Equal(NotificationDeliveryAttemptStatus.FcmAccepted, attempt.Status);
        Assert.Equal("projects/test/messages/accepted-1", attempt.ProviderMessageId);
        Assert.Equal(fixture.Claim.DeliveryKey, transport.LastRequest!.NotificationId);
        Assert.Equal(fixture.Claim.AttemptId, transport.LastRequest.DeliveryAttemptId);
    }

    [Fact]
    public async Task Provider_reported_ambiguity_is_terminal_without_blind_retry()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = FixedTransport(
            MobileNotificationTransportOutcomeKind.Ambiguous,
            "fcm-provider-outcome-unknown",
            transport: "Fcm");

        await ProcessAsync(fixture, transport);

        await using var verify = fixture.Context();
        Assert.Equal(
            NotificationRecipientStatus.Ambiguous,
            (await verify.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Ambiguous,
            (await verify.DeliveryAttempts.SingleAsync()).Status);
        Assert.Empty(await new NotificationWorkClaimer(verify)
            .ClaimRecipientsAsync(
                fixture.Now.AddHours(1),
                fixture.Now.AddHours(1).AddMinutes(2),
                10,
                CancellationToken.None));
    }

    [Fact]
    public async Task Acceptance_persistence_failure_becomes_ambiguous_without_second_send()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = AcceptedTransport();
        var failure = new FailSaveChangesOnCallInterceptor(2);

        await Assert.ThrowsAsync<DbUpdateException>(() =>
            ProcessAsync(fixture, transport, failure));
        Assert.Equal(1, transport.Calls);

        await RecoverAsync(fixture, fixture.Now.AddMinutes(3));
        var laterClaims = await ClaimAsync(
            fixture,
            fixture.Now.AddMinutes(4),
            fixture.Now.AddMinutes(6));

        await using var verify = fixture.Context();
        Assert.Empty(laterClaims);
        Assert.Equal(
            NotificationRecipientStatus.Ambiguous,
            (await verify.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Ambiguous,
            (await verify.DeliveryAttempts.SingleAsync()).Status);
        Assert.Equal(1, transport.Calls);
    }

    [Fact]
    public async Task Recipient_projection_failure_after_acceptance_repairs_without_second_send()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = AcceptedTransport();
        var failure = new FailSaveChangesOnCallInterceptor(3);

        await Assert.ThrowsAsync<DbUpdateException>(() =>
            ProcessAsync(fixture, transport, failure));

        await using (var inspect = fixture.Context())
        {
            Assert.Equal(
                NotificationDeliveryAttemptStatus.FcmAccepted,
                (await inspect.DeliveryAttempts.SingleAsync()).Status);
            Assert.Equal(
                NotificationRecipientStatus.Sending,
                (await inspect.Recipients.SingleAsync()).Status);
        }

        await RecoverAsync(fixture, fixture.Now.AddSeconds(5));

        await using var verify = fixture.Context();
        Assert.Equal(
            NotificationRecipientStatus.FcmAccepted,
            (await verify.Recipients.SingleAsync()).Status);
        Assert.Equal(1, transport.Calls);
    }

    [Fact]
    public async Task Device_log_read_failure_after_acceptance_never_replays_transport()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = AcceptedTransport();
        await ProcessAsync(fixture, transport);

        var failedContext = fixture.Context();
        var failedReader = new NotificationDeviceLogService(failedContext);
        await failedContext.DisposeAsync();
        await Assert.ThrowsAsync<ObjectDisposedException>(() =>
            failedReader.ReadForDeviceAsync(
                new NotificationApplicationContext(fixture.ApplicationId),
                fixture.DeviceId,
                CancellationToken.None));

        await using var recoveredContext = fixture.Context();
        var entries = await new NotificationDeviceLogService(recoveredContext)
            .ReadForDeviceAsync(
                new NotificationApplicationContext(fixture.ApplicationId),
                fixture.DeviceId,
                CancellationToken.None);

        Assert.Equal("FcmAccepted", Assert.Single(entries).Status);
        Assert.Equal(1, transport.Calls);
    }

    [Fact]
    public async Task Campaign_summary_failure_is_recomputed_without_second_send()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = AcceptedTransport();
        await ProcessAsync(fixture, transport);

        await using (var failing = fixture.Context(
            new FailSaveChangesOnCallInterceptor(1)))
        {
            await Assert.ThrowsAsync<DbUpdateException>(() =>
                new NotificationCampaignSummaryService(failing).RefreshAsync(
                    fixture.CampaignId,
                    fixture.Now.AddSeconds(5),
                    CancellationToken.None));
        }

        await using (var repair = fixture.Context())
        {
            Assert.True(await new NotificationCampaignSummaryService(repair)
                .RefreshNextDispatchingAsync(
                    fixture.Now.AddSeconds(6),
                    CancellationToken.None));
        }

        await using var verify = fixture.Context();
        var campaign = await verify.Campaigns.SingleAsync();
        Assert.Equal(NotificationCampaignStatus.Completed, campaign.Status);
        Assert.Equal(1, campaign.FcmAcceptedCount);
        Assert.Equal(1, transport.Calls);
    }

    [Fact]
    public async Task Crash_during_provider_call_recovers_as_ambiguous_without_retry()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = new ThrowingTransport();

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            ProcessAsync(fixture, transport));
        await RecoverAsync(fixture, fixture.Now.AddMinutes(3));

        await using var verify = fixture.Context();
        Assert.Equal(
            NotificationRecipientStatus.Ambiguous,
            (await verify.Recipients.SingleAsync()).Status);
        Assert.Empty(await new NotificationWorkClaimer(verify)
            .ClaimRecipientsAsync(
                fixture.Now.AddMinutes(4),
                fixture.Now.AddMinutes(6),
                10,
                CancellationToken.None));
    }

    [Fact]
    public async Task Stale_pre_send_worker_cannot_invoke_provider_after_new_lease()
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        await SeedAsync(root, name, now);
        var first = await ClaimSingleAsync(root, name, now, now.AddSeconds(30));
        var current = await ClaimSingleAsync(
            root,
            name,
            now.AddSeconds(31),
            now.AddMinutes(2));
        var transport = AcceptedTransport();

        var staleResult = await ProcessAsync(
            root,
            name,
            first,
            now.AddSeconds(31),
            transport);
        var currentResult = await ProcessAsync(
            root,
            name,
            current,
            now.AddSeconds(31),
            transport);

        Assert.False(staleResult.Processed);
        Assert.True(currentResult.Processed);
        Assert.Equal(1, transport.Calls);
    }

    [Fact]
    public async Task Late_provider_acceptance_cannot_overwrite_ambiguous_recovery()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = new BlockingTransport();
        var processing = ProcessAsync(fixture, transport);
        await transport.Started.Task.WaitAsync(TimeSpan.FromSeconds(5));

        await RecoverAsync(fixture, fixture.Now.AddMinutes(3));
        transport.Complete(new MobileNotificationTransportOutcome(
            MobileNotificationTransportOutcomeKind.FcmAccepted,
            ProviderMessageId: "late-provider-id",
            Transport: "Fcm"));
        var result = await processing;

        await using var verify = fixture.Context();
        Assert.False(result.Processed);
        Assert.Equal(
            NotificationRecipientStatus.Ambiguous,
            (await verify.Recipients.SingleAsync()).Status);
        var attempt = await verify.DeliveryAttempts.SingleAsync();
        Assert.Equal(NotificationDeliveryAttemptStatus.Ambiguous, attempt.Status);
        Assert.Null(attempt.ProviderMessageId);
    }

    [Fact]
    public async Task Duplicate_campaign_processing_invokes_provider_once()
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        await SeedAsync(root, name, now);
        await using var firstDb = Context(root, name);
        await using var secondDb = Context(root, name);
        var claimGroups = await Task.WhenAll(
            new NotificationWorkClaimer(firstDb).ClaimRecipientsAsync(
                now,
                now.AddMinutes(2),
                100,
                CancellationToken.None),
            new NotificationWorkClaimer(secondDb).ClaimRecipientsAsync(
                now,
                now.AddMinutes(2),
                100,
                CancellationToken.None));
        var transport = AcceptedTransport();

        foreach (var claim in claimGroups.SelectMany(group => group))
        {
            await ProcessAsync(root, name, claim, now, transport);
        }

        Assert.Equal(1, transport.Calls);
        await using var verify = Context(root, name);
        Assert.Equal(1, await verify.DeliveryAttempts.CountAsync());
    }

    [Fact]
    public async Task Expired_delivery_never_invokes_provider()
    {
        var fixture = await CreateClaimedFixtureAsync(lifetime: TimeSpan.FromMinutes(1));
        var transport = AcceptedTransport();

        await ProcessAsync(
            fixture with { Now = fixture.Now.AddMinutes(1).AddSeconds(1) },
            transport);

        Assert.Equal(0, transport.Calls);
        await using var verify = fixture.Context();
        Assert.Equal(
            NotificationRecipientStatus.Expired,
            (await verify.Recipients.SingleAsync()).Status);
        Assert.Equal(
            NotificationDeliveryAttemptStatus.Expired,
            (await verify.DeliveryAttempts.SingleAsync()).Status);
    }

    [Fact]
    public async Task Application_context_and_safe_logs_never_include_payload_or_device_secret()
    {
        var fixture = await CreateClaimedFixtureAsync();
        var transport = AcceptedTransport();
        var logger = new RecordingLogger<NotificationDeliveryAttemptProcessor>();
        await ProcessAsync(fixture, transport, logger: logger);

        Assert.Equal(
            fixture.ApplicationId,
            transport.LastRequest!.Application.ApplicationId);
        Assert.DoesNotContain(logger.Messages, message =>
            message.Contains("installation-secret", StringComparison.Ordinal)
            || message.Contains("Sensitive body", StringComparison.Ordinal));

        var propertyNames = typeof(NotificationDeliveryAttempt)
            .GetProperties()
            .Select(property => property.Name)
            .ToArray();
        Assert.DoesNotContain(propertyNames, name =>
            name.Contains("Token", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Credential", StringComparison.OrdinalIgnoreCase)
            || name.Contains("Body", StringComparison.OrdinalIgnoreCase));
    }

    private static RecordingTransport AcceptedTransport() =>
        new(new MobileNotificationTransportOutcome(
            MobileNotificationTransportOutcomeKind.FcmAccepted,
            ProviderMessageId: "projects/test/messages/provider-id",
            Transport: "Fcm"));

    private static RecordingTransport FixedTransport(
        MobileNotificationTransportOutcomeKind kind,
        string? safeErrorCode = null,
        string? providerMessageId = null,
        string? transport = null) =>
        new(new MobileNotificationTransportOutcome(
            kind,
            safeErrorCode,
            providerMessageId,
            transport));

    private static async Task<DeliveryFixture> CreateClaimedFixtureAsync(
        TimeSpan? lifetime = null)
    {
        var root = new InMemoryDatabaseRoot();
        var name = DatabaseName();
        var now = UtcNow();
        var seeded = await SeedAsync(
            root,
            name,
            now,
            lifetime: lifetime);
        var claim = await ClaimSingleAsync(
            root,
            name,
            now,
            now.AddMinutes(2));
        return new DeliveryFixture(
            root,
            name,
            now,
            seeded.ApplicationId,
            seeded.CampaignId,
            seeded.RecipientId,
            seeded.DeviceId,
            claim);
    }

    private static async Task<SeededDelivery> SeedAsync(
        InMemoryDatabaseRoot root,
        string name,
        DateTimeOffset now,
        Guid? applicationId = null,
        TimeSpan? lifetime = null)
    {
        await using var db = Context(root, name);
        var appId = applicationId ?? Guid.NewGuid();
        var campaign = Campaign(
            appId,
            now,
            lifetime ?? TimeSpan.FromDays(7));
        var deviceId = Guid.NewGuid();
        var recipient = NotificationRecipient.Create(
            appId,
            campaign.Id,
            deviceId,
            "installation-secret",
            "android",
            "Device",
            now,
            campaign.ExpiresAtUtc);
        db.AddRange(campaign, recipient);
        await db.SaveChangesAsync();
        return new SeededDelivery(
            appId,
            campaign.Id,
            recipient.Id,
            deviceId,
            recipient.DeliveryKey);
    }

    private static NotificationCampaign Campaign(
        Guid applicationId,
        DateTimeOffset now,
        TimeSpan lifetime)
    {
        var campaign = NotificationCampaign.Create(
            applicationId,
            $"app-{applicationId:N}",
            "Application",
            NotificationAudienceKind.AllCurrentActiveDevices,
            now,
            "عنوان",
            "Title",
            "نص حساس",
            "Sensitive body",
            "general",
            NotificationPriority.Normal,
            Guid.NewGuid().ToString("N"),
            new string('A', 64),
            Guid.NewGuid(),
            "Administrator",
            now,
            now + lifetime,
            1,
            1,
            1);
        campaign.ClaimAudience(Guid.NewGuid(), now.AddMinutes(2), now);
        campaign.AdvanceAudience(null, 0, true);
        return campaign;
    }

    private static async Task<ClaimedNotificationWork> ClaimSingleAsync(
        InMemoryDatabaseRoot root,
        string name,
        DateTimeOffset now,
        DateTimeOffset leaseExpiresAtUtc)
    {
        await using var db = Context(root, name);
        var claims = await new NotificationWorkClaimer(db)
            .ClaimRecipientsAsync(
                now,
                leaseExpiresAtUtc,
                10,
                CancellationToken.None);
        return Assert.Single(claims);
    }

    private static async Task<IReadOnlyList<ClaimedNotificationWork>> ClaimAsync(
        DeliveryFixture fixture,
        DateTimeOffset now,
        DateTimeOffset leaseExpiresAtUtc)
    {
        await using var db = fixture.Context();
        return await new NotificationWorkClaimer(db)
            .ClaimRecipientsAsync(
                now,
                leaseExpiresAtUtc,
                10,
                CancellationToken.None);
    }

    private static Task<NotificationAttemptResult> ProcessAsync(
        DeliveryFixture fixture,
        IMobileNotificationTransport transport,
        SaveChangesInterceptor? interceptor = null,
        ILogger<NotificationDeliveryAttemptProcessor>? logger = null) =>
        ProcessAsync(
            fixture.Root,
            fixture.DatabaseName,
            fixture.Claim,
            fixture.Now,
            transport,
            interceptor,
            logger);

    private static async Task<NotificationAttemptResult> ProcessAsync(
        InMemoryDatabaseRoot root,
        string name,
        ClaimedNotificationWork claim,
        DateTimeOffset now,
        IMobileNotificationTransport transport,
        SaveChangesInterceptor? interceptor = null,
        ILogger<NotificationDeliveryAttemptProcessor>? logger = null)
    {
        await using var db = Context(root, name, interceptor);
        return await new NotificationDeliveryAttemptProcessor(
                db,
                transport,
                Options.Create(OptionsForTests()),
                new MutableTimeProvider(now),
                logger ?? NullLogger<NotificationDeliveryAttemptProcessor>.Instance)
            .ProcessAsync(claim, CancellationToken.None);
    }

    private static async Task RecoverAsync(
        DeliveryFixture fixture,
        DateTimeOffset now)
    {
        await using var db = fixture.Context();
        await new NotificationDeliveryRecoveryProcessor(
                db,
                Options.Create(OptionsForTests()),
                NullLogger<NotificationDeliveryRecoveryProcessor>.Instance)
            .RecoverBatchAsync(now, 100, CancellationToken.None);
    }

    private static NotificationsDbContext Context(
        InMemoryDatabaseRoot root,
        string name,
        SaveChangesInterceptor? interceptor = null)
    {
        var builder = new DbContextOptionsBuilder<NotificationsDbContext>()
            .UseInMemoryDatabase(name, root);
        if (interceptor is not null)
        {
            builder.AddInterceptors(interceptor);
        }

        return new NotificationsDbContext(builder.Options);
    }

    private static NotificationCampaignOptions OptionsForTests() => new()
    {
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

    private static DateTimeOffset UtcNow() =>
        new(2026, 8, 30, 10, 0, 0, TimeSpan.Zero);

    private static string DatabaseName() =>
        $"notification-delivery-{Guid.NewGuid():N}";

    private sealed record SeededDelivery(
        Guid ApplicationId,
        Guid CampaignId,
        Guid RecipientId,
        Guid DeviceId,
        string DeliveryKey);

    private sealed record DeliveryFixture(
        InMemoryDatabaseRoot Root,
        string DatabaseName,
        DateTimeOffset Now,
        Guid ApplicationId,
        Guid CampaignId,
        Guid RecipientId,
        Guid DeviceId,
        ClaimedNotificationWork Claim)
    {
        public NotificationsDbContext Context(
            SaveChangesInterceptor? interceptor = null) =>
            NotificationDeliveryDurabilityTests.Context(
                Root,
                DatabaseName,
                interceptor);
    }

    private sealed class RecordingTransport(
        MobileNotificationTransportOutcome outcome)
        : IMobileNotificationTransport
    {
        public int Calls { get; private set; }
        public MobileNotificationTransportRequest? LastRequest { get; private set; }

        public Task<MobileNotificationTransportOutcome> DispatchAsync(
            MobileNotificationTransportRequest request,
            CancellationToken cancellationToken)
        {
            Calls++;
            LastRequest = request;
            return Task.FromResult(outcome);
        }
    }

    private sealed class ThrowingTransport : IMobileNotificationTransport
    {
        public Task<MobileNotificationTransportOutcome> DispatchAsync(
            MobileNotificationTransportRequest request,
            CancellationToken cancellationToken) =>
            throw new InvalidOperationException(
                "Synthetic provider-call interruption.");
    }

    private sealed class BlockingTransport : IMobileNotificationTransport
    {
        private readonly TaskCompletionSource<MobileNotificationTransportOutcome>
            _completion = new(
                TaskCreationOptions.RunContinuationsAsynchronously);

        public TaskCompletionSource<bool> Started { get; } = new(
            TaskCreationOptions.RunContinuationsAsynchronously);

        public Task<MobileNotificationTransportOutcome> DispatchAsync(
            MobileNotificationTransportRequest request,
            CancellationToken cancellationToken)
        {
            Started.TrySetResult(true);
            return _completion.Task.WaitAsync(cancellationToken);
        }

        public void Complete(MobileNotificationTransportOutcome outcome) =>
            _completion.TrySetResult(outcome);
    }

    private sealed class FailSaveChangesOnCallInterceptor(int failOnCall)
        : SaveChangesInterceptor
    {
        private int _calls;

        public override ValueTask<InterceptionResult<int>> SavingChangesAsync(
            DbContextEventData eventData,
            InterceptionResult<int> result,
            CancellationToken cancellationToken = default)
        {
            _calls++;
            if (_calls == failOnCall)
            {
                throw new DbUpdateException(
                    "Synthetic local persistence failure.");
            }

            return base.SavingChangesAsync(
                eventData,
                result,
                cancellationToken);
        }
    }

    private sealed class RecordingLogger<T> : ILogger<T>
    {
        public List<string> Messages { get; } = [];

        public IDisposable? BeginScope<TState>(TState state)
            where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => true;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            Messages.Add(formatter(state, exception));
        }
    }

    private sealed class MutableTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
