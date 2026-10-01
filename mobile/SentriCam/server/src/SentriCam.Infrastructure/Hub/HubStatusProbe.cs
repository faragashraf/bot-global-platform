using Microsoft.EntityFrameworkCore;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Persistence;

namespace SentriCam.Infrastructure.Hub;

public sealed class HubStatusProbe(
    IHubSetupStore store,
    SentriCamDbContext database,
    IMediaEngineProbe mediaEngineProbe) : IHubStatusProbe
{
    public async Task<HubStatus> ReadAsync(CancellationToken cancellationToken = default)
    {
        var configuration = store.Current;
        var storage = StorageStatus(configuration.Draft);
        var databaseStatus = await DatabaseStatusAsync(configuration, cancellationToken);
        var media = mediaEngineProbe.Detect(configuration.Draft.Media);
        var deviceCount = 0;
        if (databaseStatus.Status == "ready")
        {
            deviceCount = await database.Devices.CountAsync(cancellationToken);
        }
        return new HubStatus(
            configuration.IsConfigured
                && storage.Status != "broken"
                && databaseStatus.Status == "ready",
            configuration.Draft.HubName,
            configuration.Draft.Mode,
            storage,
            databaseStatus,
            media,
            deviceCount,
            deviceCount == 0
                ? "Waiting for devices"
                : deviceCount == 1 ? "1 device connected" : $"{deviceCount} devices connected",
            FolderLabel(configuration.Draft.RecordingFolder),
            configuration.Draft.StoragePolicy,
            configuration.Draft.StartAutomatically,
            configuration.ConfiguredAtUtc);
    }

    private async Task<HubCapabilityStatus> DatabaseStatusAsync(
        HubRuntimeConfiguration configuration,
        CancellationToken cancellationToken)
    {
        try
        {
            var connected = await database.Database.CanConnectAsync(cancellationToken);
            var home = configuration.Draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase);
            return connected
                ? new HubCapabilityStatus(
                    "ready",
                    "Ready",
                    home ? "Private Hub data is healthy" : $"{ProviderLabel(configuration.Draft.Database.Provider)} connected")
                : new HubCapabilityStatus("broken", "Unavailable", "The Hub data store could not be reached.");
        }
        catch (Exception exception) when (exception is InvalidOperationException or TimeoutException)
        {
            return new HubCapabilityStatus("broken", "Unavailable", "The Hub data store could not be reached.");
        }
    }

    private static HubCapabilityStatus StorageStatus(HubSetupDraft draft)
    {
        try
        {
            if (!Directory.Exists(draft.RecordingFolder))
            {
                return new HubCapabilityStatus(
                    "broken",
                    "Unavailable",
                    "The recording folder is missing. Repair Hub setup to restore it.");
            }
            var root = Path.GetPathRoot(Path.GetFullPath(draft.RecordingFolder));
            if (string.IsNullOrEmpty(root))
            {
                return new HubCapabilityStatus("broken", "Unavailable", "The recording folder is invalid.");
            }
            var drive = new DriveInfo(root);
            var freeGb = drive.AvailableFreeSpace / 1_073_741_824d;
            var status = freeGb < draft.MinimumFreeSpaceGb ? "warning" : "ready";
            return new HubCapabilityStatus(
                status,
                status == "ready" ? "Healthy" : "Space running low",
                $"{freeGb:0.#} GB free · {StoragePolicyLabel(draft.StoragePolicy)} policy");
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException or ArgumentException)
        {
            return new HubCapabilityStatus("broken", "Unavailable", "The recording folder cannot be accessed.");
        }
    }

    private static string ProviderLabel(string provider) => provider.ToLowerInvariant() switch
    {
        HubDatabaseProviders.SqlServer => "SQL Server",
        HubDatabaseProviders.PostgreSql => "PostgreSQL",
        _ => "SQLite",
    };

    private static string StoragePolicyLabel(string policy) => policy.ToLowerInvariant() switch
    {
        HubStoragePolicies.KeepMore => "Keep more",
        HubStoragePolicies.SpaceSaver => "Space saver",
        HubStoragePolicies.Custom => "Custom",
        _ => "Balanced",
    };

    private static string FolderLabel(string path)
    {
        var label = Path.GetFileName(Path.TrimEndingDirectorySeparator(path));
        return string.IsNullOrWhiteSpace(label) ? "Configured folder" : label;
    }
}
