using SentriCam.Application.Commands;
using SentriCam.Application.Common;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Hub;
using SentriCam.Contracts.Registration;

namespace SentriCam.Tests.Application;

public sealed class PairingEngineTests
{
    [Fact]
    public async Task PairingSessionIsShortLivedOneUseAndDelegatesToRegistration()
    {
        var now = new DateTimeOffset(2026, 8, 1, 12, 0, 0, TimeSpan.Zero);
        var timeProvider = new FixedTimeProvider(now);
        var handler = new RecordingRegistrationHandler();
        var store = new ConfiguredStore();
        var engine = new PairingEngine(
            store,
            new FixedAddressResolver(),
            new InMemoryPairingSessionStore(),
            handler,
            timeProvider);

        var session = engine.CreateSession();
        var codeStart = session.Payload.LastIndexOf("&code=", StringComparison.Ordinal) + 6;
        var codeEnd = session.Payload.IndexOf('&', codeStart);
        var code = session.Payload[codeStart..codeEnd];
        var request = new CompletePairingRequest(code, Registration());

        var result = await engine.CompleteAsync(request, TestContext.Current.CancellationToken);

        Assert.Equal(now.AddMinutes(5), session.ExpiresAtUtc);
        Assert.False(store.Current.Draft.Network.HttpsEnabled);
        Assert.Equal(5173, store.Current.Draft.Network.Port);
        Assert.StartsWith("sentricam://pair?v=1&hub=", session.Payload, StringComparison.Ordinal);
        Assert.Equal("https://cedar-hub.local", session.HubAddress);
        Assert.Contains("hub=https%3A%2F%2Fcedar-hub.local", session.Payload, StringComparison.Ordinal);
        Assert.DoesNotContain("5173", session.Payload, StringComparison.Ordinal);
        Assert.DoesNotContain("http%3A", session.Payload, StringComparison.Ordinal);
        Assert.Equal(handler.Result.DeviceId, result.DeviceId);
        Assert.Equal(session.HubFingerprint, result.HubFingerprint);
        Assert.Equal("1", session.ProtocolVersion);
        Assert.Contains("&fp=", session.Payload, StringComparison.Ordinal);
        Assert.Equal(1, handler.CallCount);
        await Assert.ThrowsAsync<AccessDeniedException>(
            () => engine.CompleteAsync(request, TestContext.Current.CancellationToken));
    }

    private static RegistrationRequest Registration() => new(
        "Hall Camera",
        new DeviceIdentityContract(
            "installation-1",
            "Google",
            "Pixel",
            "Android",
            "16",
            "0.3.0"));

    private sealed class ConfiguredStore : IHubSetupStore
    {
        public HubRuntimeConfiguration Current { get; } = new(
            HubSetupStates.Completed,
            new HubSetupDraft(
                HubModes.Home,
                "Cedar Home",
                "/recordings",
                HubStoragePolicies.Balanced,
                30,
                10,
                true,
                true,
                new HubDatabaseSettings(),
                new HubNetworkSettings(),
                new HubMediaSettings()),
            "Data Source=sentricam.db",
            Convert.ToBase64String(new byte[64]),
            Convert.ToBase64String(Enumerable.Repeat((byte)0x53, 32).ToArray()),
            Convert.ToBase64String(new byte[32]),
            DateTimeOffset.UtcNow);

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
            CancellationToken cancellationToken = default) => Task.CompletedTask;

        public Task CompleteAsync(
            HubSetupDraft draft,
            string databaseConnectionString,
            CancellationToken cancellationToken = default) => Task.CompletedTask;
    }

    private sealed class FixedAddressResolver : IAdvertisedHubUrlResolver
    {
        public Uri Resolve() => new("https://cedar-hub.local");
    }

    private sealed class RecordingRegistrationHandler : IRegisterDeviceCommandHandler
    {
        public RegistrationResult Result { get; } = new(
            Guid.Parse("11111111-1111-1111-1111-111111111111"),
            true,
            "device-token",
            DateTimeOffset.UtcNow.AddHours(1),
            DateTimeOffset.UtcNow);

        public int CallCount { get; private set; }

        public Task<RegistrationResult> HandleAsync(
            RegisterDevice command,
            CancellationToken cancellationToken = default)
        {
            CallCount++;
            return Task.FromResult(Result);
        }
    }

    private sealed class FixedTimeProvider(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }
}
