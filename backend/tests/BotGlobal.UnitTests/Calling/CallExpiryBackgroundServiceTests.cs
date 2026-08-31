using BotGlobal.Calling.Realtime;
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
}
