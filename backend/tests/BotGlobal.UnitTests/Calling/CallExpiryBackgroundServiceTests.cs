using BotGlobal.Calling.Realtime;
using BotGlobal.Calling.Application;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallExpiryBackgroundServiceTests
{
    [Fact]
    public async Task Expiry_dispatches_once_when_processing_repeats()
    {
        var registry = new CallSessionRegistry();
        var caller = new ApplicationIdentityDescriptor(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "caller-subject",
            "test-app",
            "Caller",
            false);
        var callee = new CallingParticipantDescriptor(
            Guid.NewGuid(),
            "test-app",
            "callee-subject",
            "Callee",
            true);
        var now = DateTimeOffset.Parse("2026-08-31T12:00:00Z");
        registry.Connected("caller-connection", caller);
        var started = registry.Start("caller-connection", callee, now, TimeSpan.FromSeconds(45));
        registry.Disconnected("caller-connection");

        var dispatcher = new RecordingDispatcher();
        var services = new ServiceCollection()
            .AddSingleton<IIncomingCallNotificationDispatcher>(dispatcher)
            .BuildServiceProvider();
        await using (services)
        {
            var service = new CallExpiryBackgroundService(
                registry,
                null!,
                services.GetRequiredService<IServiceScopeFactory>(),
                TimeProvider.System,
                NullLogger<CallExpiryBackgroundService>.Instance);

            await service.ProcessExpiredCallsAsync(now.AddSeconds(46), CancellationToken.None);
            await service.ProcessExpiredCallsAsync(now.AddMinutes(2), CancellationToken.None);
        }

        var notification = Assert.Single(dispatcher.Notifications);
        Assert.Equal(started.Session.CallId, notification.CallId);
        Assert.Equal(IncomingCallNotificationKind.Expired, notification.Kind);
        Assert.Equal(CallSessionRegistry.CallStatus.Expired, started.Session.Status);
    }

    [Fact]
    public async Task History_failure_does_not_suppress_existing_expiry_notification_behavior()
    {
        var registry = new CallSessionRegistry();
        var now = DateTimeOffset.Parse("2026-09-01T12:00:00Z");
        registry.Connected("caller", new ApplicationIdentityDescriptor(Guid.NewGuid(), null, "subject", "nqrb", "Caller", false));
        registry.Start("caller", new CallingParticipantDescriptor(Guid.NewGuid(), "nqrb", "callee", "Callee", true), now, TimeSpan.FromSeconds(1));
        registry.Disconnected("caller");
        var dispatcher = new RecordingDispatcher();
        var services = new ServiceCollection()
            .AddSingleton<ICallActivityService>(new ThrowingActivity())
            .AddSingleton<IIncomingCallNotificationDispatcher>(dispatcher)
            .BuildServiceProvider();
        await using (services)
        {
            var worker = new CallExpiryBackgroundService(registry, null!, services.GetRequiredService<IServiceScopeFactory>(),
                TimeProvider.System, NullLogger<CallExpiryBackgroundService>.Instance);

            await worker.ProcessExpiredCallsAsync(now.AddSeconds(2), default);
        }

        Assert.Single(dispatcher.Notifications);
    }

    private sealed class RecordingDispatcher : IIncomingCallNotificationDispatcher
    {
        public List<IncomingCallNotification> Notifications { get; } = [];

        public Task DispatchAsync(
            IncomingCallNotification notification,
            CancellationToken cancellationToken)
        {
            Notifications.Add(notification);
            return Task.CompletedTask;
        }
    }

    private sealed class ThrowingActivity : ICallActivityService
    {
        public Task FinishAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => throw new InvalidOperationException("synthetic persistence failure");
        public Task StartAsync(CallSessionRegistry.Session session, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task AnswerAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task JoinedAsync(CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }
}
