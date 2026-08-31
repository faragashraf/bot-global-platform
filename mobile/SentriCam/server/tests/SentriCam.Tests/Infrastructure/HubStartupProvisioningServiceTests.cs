using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Hub;

namespace SentriCam.Tests.Infrastructure;

public sealed class HubStartupProvisioningServiceTests
{
    [Theory]
    [InlineData(HubSetupStates.NotStarted)]
    [InlineData(HubSetupStates.InProgress)]
    [InlineData(HubSetupStates.Failed)]
    [InlineData(HubSetupStates.NeedsRepair)]
    public async Task IncompleteSetupNeverCreatesAProvisioningScope(string state)
    {
        var store = new StateStore(state, configuredAtUtc: null);
        var service = new HubStartupProvisioningService(
            store,
            new ThrowingScopeFactory(),
            NullLogger<HubStartupProvisioningService>.Instance);

        await service.StartAsync(TestContext.Current.CancellationToken);

        Assert.False(store.Current.IsConfigured);
        Assert.Equal(0, store.RepairCount);
    }

    [Fact]
    public async Task CompletedSetupRunsHealthCheckWithoutResolvingProvisioner()
    {
        var store = new StateStore(HubSetupStates.Completed, DateTimeOffset.UtcNow);
        var probe = new RecordingStatusProbe(isReady: true);
        var services = new ServiceCollection()
            .AddSingleton<IHubStatusProbe>(probe)
            .BuildServiceProvider();
        var service = new HubStartupProvisioningService(
            store,
            services.GetRequiredService<IServiceScopeFactory>(),
            NullLogger<HubStartupProvisioningService>.Instance);

        await service.StartAsync(TestContext.Current.CancellationToken);

        Assert.Equal(1, probe.ReadCount);
        Assert.Equal(0, store.RepairCount);
        Assert.True(store.Current.IsConfigured);
    }

    [Fact]
    public async Task FailedCompletedHealthCheckMovesHubToNeedsRepair()
    {
        var store = new StateStore(HubSetupStates.Completed, DateTimeOffset.UtcNow);
        var services = new ServiceCollection()
            .AddSingleton<IHubStatusProbe>(new RecordingStatusProbe(isReady: false))
            .BuildServiceProvider();
        var service = new HubStartupProvisioningService(
            store,
            services.GetRequiredService<IServiceScopeFactory>(),
            NullLogger<HubStartupProvisioningService>.Instance);

        await service.StartAsync(TestContext.Current.CancellationToken);

        Assert.Equal(HubSetupStates.NeedsRepair, store.Current.State);
        Assert.False(store.Current.IsConfigured);
        Assert.Equal(1, store.RepairCount);
    }

    private sealed class ThrowingScopeFactory : IServiceScopeFactory
    {
        public IServiceScope CreateScope() =>
            throw new InvalidOperationException("Incomplete setup attempted startup provisioning.");
    }

    private sealed class RecordingStatusProbe(bool isReady) : IHubStatusProbe
    {
        public int ReadCount { get; private set; }

        public Task<HubStatus> ReadAsync(CancellationToken cancellationToken = default)
        {
            ReadCount++;
            return Task.FromResult(new HubStatus(
                isReady,
                "Test Home",
                HubModes.Home,
                new HubCapabilityStatus("ready", "Ready"),
                new HubCapabilityStatus(isReady ? "ready" : "broken", isReady ? "Ready" : "Unavailable"),
                new HubCapabilityStatus("ready", "Ready"),
                0,
                "Waiting for devices",
                "/tmp/recordings",
                HubStoragePolicies.Balanced,
                true,
                DateTimeOffset.UtcNow));
        }
    }

    private sealed class StateStore : IHubSetupStore
    {
        public StateStore(string state, DateTimeOffset? configuredAtUtc)
        {
            Current = new HubRuntimeConfiguration(
                state,
                new HubSetupDraft(
                    HubModes.Home,
                    "Test Home",
                    "/tmp/recordings",
                    HubStoragePolicies.Balanced,
                    30,
                    10,
                    true,
                    true,
                    new HubDatabaseSettings(
                        HubDatabaseProviders.Sqlite,
                        DatabaseName: "/tmp/sentricam.db"),
                    new HubNetworkSettings(),
                    new HubMediaSettings()),
                "Data Source=/tmp/sentricam.db",
                Convert.ToBase64String(new byte[64]),
                Convert.ToBase64String(new byte[32]),
                Convert.ToBase64String(new byte[32]),
                configuredAtUtc);
        }

        public HubRuntimeConfiguration Current { get; private set; }

        public int RepairCount { get; private set; }

        public Task SaveDraftAsync(HubSetupDraft draft, CancellationToken cancellationToken = default) =>
            Task.CompletedTask;

        public Task MarkFailedAsync(
            HubSetupDraft draft,
            string code,
            string message,
            CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task MarkNeedsRepairAsync(
            string code,
            string message,
            CancellationToken cancellationToken = default)
        {
            RepairCount++;
            Current = Current with
            {
                State = HubSetupStates.NeedsRepair,
                Failure = new HubSetupFailure(code, message, DateTimeOffset.UtcNow),
            };
            return Task.CompletedTask;
        }

        public Task CompleteAsync(
            HubSetupDraft draft,
            string databaseConnectionString,
            CancellationToken cancellationToken = default) => Task.CompletedTask;
    }
}
