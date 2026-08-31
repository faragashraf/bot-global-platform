namespace SentriCam.Contracts.Hub;

public static class HubModes
{
    public const string Home = "home";
    public const string Office = "office";
    public const string Enterprise = "enterprise";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(
        [Home, Office, Enterprise],
        StringComparer.OrdinalIgnoreCase);
}

public static class HubDatabaseProviders
{
    public const string Sqlite = "sqlite";
    public const string SqlServer = "sqlserver";
    public const string PostgreSql = "postgresql";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(
        [Sqlite, SqlServer, PostgreSql],
        StringComparer.OrdinalIgnoreCase);
}

public static class HubStoragePolicies
{
    public const string Balanced = "balanced";
    public const string KeepMore = "keep-more";
    public const string SpaceSaver = "space-saver";
    public const string Custom = "custom";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(
        [Balanced, KeepMore, SpaceSaver, Custom],
        StringComparer.OrdinalIgnoreCase);
}

public static class HubSetupStates
{
    public const string NotStarted = "NotStarted";
    public const string InProgress = "InProgress";
    public const string Completed = "Completed";
    public const string Failed = "Failed";
    public const string NeedsRepair = "NeedsRepair";

    public static readonly IReadOnlySet<string> All = new HashSet<string>(
        [NotStarted, InProgress, Completed, Failed, NeedsRepair],
        StringComparer.Ordinal);
}

public sealed record HubDatabaseSettings(
    string Provider = HubDatabaseProviders.Sqlite,
    string? Host = null,
    int? Port = null,
    string? DatabaseName = null,
    string? Username = null,
    string? Password = null,
    bool Encrypt = true,
    bool TrustServerCertificate = false,
    bool UseIntegratedSecurity = false,
    bool HasStoredPassword = false);

public sealed record HubNetworkSettings(
    bool HttpsEnabled = false,
    int Port = 5173,
    string? CertificatePath = null,
    bool GenerateCertificate = true);

public sealed record HubMediaSettings(
    bool PreferBundledEngine = true,
    string? CustomPath = null);

public sealed record HubSetupDraft(
    string Mode,
    string HubName,
    string RecordingFolder,
    string StoragePolicy,
    int RetentionDays,
    int MinimumFreeSpaceGb,
    bool StartAutomatically,
    bool OpenDashboard,
    HubDatabaseSettings Database,
    HubNetworkSettings Network,
    HubMediaSettings Media,
    string CurrentStep = "welcome");

public sealed record HubSetupState(
    bool IsConfigured,
    string State,
    HubSetupDraft Draft,
    string DefaultRecordingFolder,
    IReadOnlyCollection<string> SupportedDatabaseProviders,
    string Version,
    DateTimeOffset? ConfiguredAtUtc = null,
    HubSetupFailure? Failure = null);

public sealed record HubSetupFailure(
    string Code,
    string Message,
    DateTimeOffset FailedAtUtc,
    bool CanRetry = true);

public sealed record HubConfigurationResult(
    bool IsConfigured,
    string HubName,
    string DashboardPath,
    bool RestartRequired,
    IReadOnlyCollection<string> CompletedActions);

public sealed record HubConnectionTestRequest(HubDatabaseSettings Database);

public sealed record HubConnectionTestResult(
    bool Success,
    string Status,
    string Message,
    long? LatencyMilliseconds = null);

public sealed record HubCapabilityStatus(
    string Status,
    string Label,
    string? Detail = null);

public sealed record HubStatus(
    bool IsReady,
    string HubName,
    string Mode,
    HubCapabilityStatus Storage,
    HubCapabilityStatus Database,
    HubCapabilityStatus MediaEngine,
    int ConnectedDevices,
    string DeviceMessage,
    string RecordingFolder,
    string StoragePolicy,
    bool StartAutomatically,
    DateTimeOffset? ConfiguredAtUtc);

public sealed record PairingSession(
    string Payload,
    string HubName,
    string HubAddress,
    DateTimeOffset ExpiresAtUtc,
    string ProtocolVersion,
    string HubFingerprint);

public sealed record CompletePairingRequest(
    string PairingCode,
    Registration.RegistrationRequest Device);
