using SentriCam.Contracts.Hub;

namespace SentriCam.Application.Hub;

public sealed record HubRuntimeConfiguration(
    string State,
    HubSetupDraft Draft,
    string DatabaseConnectionString,
    string JwtSigningKey,
    string HubSecret,
    string CertificatePassword,
    DateTimeOffset? ConfiguredAtUtc,
    HubSetupFailure? Failure = null)
{
    public bool IsConfigured =>
        State.Equals(HubSetupStates.Completed, StringComparison.Ordinal)
        && ConfiguredAtUtc is not null;
}

public interface IHubSetupStore
{
    HubRuntimeConfiguration Current { get; }

    Task SaveDraftAsync(HubSetupDraft draft, CancellationToken cancellationToken = default);

    Task MarkFailedAsync(
        HubSetupDraft draft,
        string code,
        string message,
        CancellationToken cancellationToken = default);

    Task MarkNeedsRepairAsync(
        string code,
        string message,
        CancellationToken cancellationToken = default);

    Task CompleteAsync(
        HubSetupDraft draft,
        string databaseConnectionString,
        CancellationToken cancellationToken = default);
}

public interface IHubProvisioner
{
    Task<HubConnectionTestResult> TestDatabaseAsync(
        HubDatabaseSettings database,
        CancellationToken cancellationToken = default);

    Task<IReadOnlyCollection<string>> ProvisionAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken = default);

    string BuildConnectionString(HubSetupDraft draft);
}

public interface IHubStatusProbe
{
    Task<HubStatus> ReadAsync(CancellationToken cancellationToken = default);
}

public interface IAdvertisedHubUrlResolver
{
    Uri Resolve();
}

public interface IMediaEngineProbe
{
    HubCapabilityStatus Detect(HubMediaSettings settings);

    string? ResolveExecutable(HubMediaSettings settings);
}
