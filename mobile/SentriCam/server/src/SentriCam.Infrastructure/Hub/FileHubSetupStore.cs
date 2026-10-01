using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;
using Npgsql;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;

namespace SentriCam.Infrastructure.Hub;

public sealed class FileHubSetupStore : IHubSetupStore, IDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = true,
    };
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly string _settingsPath;
    private readonly string _secretsPath;
    private readonly TimeProvider _timeProvider;
    private HubRuntimeConfiguration _current;

    private FileHubSetupStore(
        string rootPath,
        HubRuntimeConfiguration initial,
        TimeProvider timeProvider)
    {
        RootPath = rootPath;
        _settingsPath = Path.Combine(rootPath, "hub-settings.json");
        _secretsPath = Path.Combine(rootPath, "hub-secrets.json");
        _current = initial;
        _timeProvider = timeProvider;
    }

    public string RootPath { get; }

    public HubRuntimeConfiguration Current => Volatile.Read(ref _current);

    public static FileHubSetupStore Bootstrap(
        IConfigurationManager configuration,
        string contentRootPath,
        bool isDevelopment = false,
        TimeProvider? timeProvider = null)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        var configuredRoot = NullIfWhiteSpace(configuration["SentriCam:Home"]);
        var freshInstall = configuration.GetValue<bool>("SentriCam:FreshInstall");
        var root = Path.GetFullPath(
            configuredRoot ?? Path.Combine(contentRootPath, "data", "hub"));
        ValidateFreshInstallProfile(root, configuredRoot, freshInstall, isDevelopment);

        var settingsPath = Path.Combine(root, "hub-settings.json");
        var secretsPath = Path.Combine(root, "hub-secrets.json");
        var persisted = freshInstall ? null : Read<HubSettingsDocument>(settingsPath);
        var secrets = freshInstall ? null : Read<HubSecretsDocument>(secretsPath);
        var databasePath = Path.Combine(root, "data", "sentricam.db");
        var defaultDraft = CreateDefaultDraft(databasePath);
        var now = (timeProvider ?? TimeProvider.System).GetUtcNow();

        var draft = persisted?.Draft ?? defaultDraft;
        var state = ResolvePersistedState(persisted);
        var configuredAtUtc = persisted?.ConfiguredAtUtc;
        var failure = persisted?.Failure;
        var databaseConnectionString = secrets?.DatabaseConnectionString
            ?? SqliteConnectionString(databasePath);
        var jwtSigningKey = secrets?.JwtSigningKey
            ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(64));
        var hubSecret = secrets?.HubSecret
            ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var certificatePassword = secrets?.CertificatePassword
            ?? Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));

        if (persisted is null
            && !freshInstall
            && configuration.GetValue<bool>("SentriCam:UseLegacyConfiguration"))
        {
            if (TryReadLegacyConfiguration(
                    configuration,
                    defaultDraft,
                    now,
                    out var legacyDraft,
                    out var legacyConnection,
                    out var legacySigningKey,
                    out configuredAtUtc,
                    out var legacyFailure))
            {
                draft = legacyDraft;
                databaseConnectionString = legacyConnection;
                jwtSigningKey = legacySigningKey;
                state = HubSetupStates.Completed;
            }
            else
            {
                draft = defaultDraft;
                state = HubSetupStates.NeedsRepair;
                configuredAtUtc = null;
                failure = legacyFailure;
            }
        }

        if (draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase)
            && !draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            draft = UseHomeDatabase(draft, databasePath);
            state = HubSetupStates.NeedsRepair;
            configuredAtUtc = null;
            failure = RepairFailure(
                "mixed_home_database",
                "Home setup contained external database settings. Review the restored Home choices and try setup again.",
                now);
            databaseConnectionString = SqliteConnectionString(databasePath);
        }

        draft = NormalizeSqlitePath(draft, databasePath);
        if (!string.IsNullOrWhiteSpace(secrets?.DatabasePassword))
        {
            draft = draft with
            {
                Database = draft.Database with
                {
                    Password = secrets.DatabasePassword,
                    HasStoredPassword = true,
                },
            };
        }

        if (state.Equals(HubSetupStates.Completed, StringComparison.Ordinal)
            && !IsValidCompletion(
                draft,
                configuredAtUtc,
                databaseConnectionString,
                jwtSigningKey,
                hubSecret,
                certificatePassword))
        {
            state = HubSetupStates.NeedsRepair;
            configuredAtUtc = null;
            failure = RepairFailure(
                "incomplete_completion",
                "The saved Hub setup is incomplete. Review the saved choices and run setup again.",
                now);
        }

        if (state.Equals(HubSetupStates.Failed, StringComparison.Ordinal) && failure is null)
        {
            failure = RepairFailure(
                "setup_failed",
                "The previous setup attempt did not finish. Review the saved choices and try again.",
                now);
        }

        var initial = new HubRuntimeConfiguration(
            state,
            draft,
            databaseConnectionString,
            jwtSigningKey,
            hubSecret,
            certificatePassword,
            configuredAtUtc,
            failure);
        var store = new FileHubSetupStore(root, initial, timeProvider ?? TimeProvider.System);
        store.ApplyConfiguration(configuration);
        return store;
    }

    public async Task SaveDraftAsync(HubSetupDraft draft, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            var current = Current;
            var savedDraft = PreservePasswordAndNormalize(draft, current);
            var updated = current.IsConfigured
                ? current with { Draft = savedDraft }
                : current with
                {
                    State = HubSetupStates.InProgress,
                    Draft = savedDraft,
                    ConfiguredAtUtc = null,
                    Failure = null,
                };
            await PersistAsync(updated, cancellationToken);
            Volatile.Write(ref _current, updated);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public async Task MarkFailedAsync(
        HubSetupDraft draft,
        string code,
        string message,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            var current = Current;
            var failedDraft = PreservePasswordAndNormalize(draft, current);
            if (failedDraft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase)
                && !failedDraft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
            {
                failedDraft = UseHomeDatabase(
                    failedDraft,
                    Path.Combine(RootPath, "data", "sentricam.db"));
            }
            var updated = current with
            {
                State = HubSetupStates.Failed,
                Draft = failedDraft,
                ConfiguredAtUtc = null,
                Failure = RepairFailure(code, message, _timeProvider.GetUtcNow()),
            };
            await PersistAsync(updated, cancellationToken);
            Volatile.Write(ref _current, updated);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public async Task MarkNeedsRepairAsync(
        string code,
        string message,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(code);
        ArgumentException.ThrowIfNullOrWhiteSpace(message);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            var current = Current;
            var updated = current with
            {
                State = HubSetupStates.NeedsRepair,
                Failure = RepairFailure(code, message, _timeProvider.GetUtcNow()),
            };
            await PersistAsync(updated, cancellationToken);
            Volatile.Write(ref _current, updated);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public async Task CompleteAsync(
        HubSetupDraft draft,
        string databaseConnectionString,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        ArgumentException.ThrowIfNullOrWhiteSpace(databaseConnectionString);
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            var current = Current;
            var completedDraft = PreservePasswordAndNormalize(draft, current);
            var configuredAtUtc = _timeProvider.GetUtcNow();
            var updated = current with
            {
                State = HubSetupStates.Completed,
                Draft = completedDraft,
                DatabaseConnectionString = databaseConnectionString,
                ConfiguredAtUtc = configuredAtUtc,
                Failure = null,
            };
            if (!IsValidCompletion(
                    completedDraft,
                    configuredAtUtc,
                    databaseConnectionString,
                    updated.JwtSigningKey,
                    updated.HubSecret,
                    updated.CertificatePassword))
            {
                throw new InvalidOperationException("Hub setup cannot be completed until all required configuration is valid.");
            }
            await PersistAsync(updated, cancellationToken);
            Volatile.Write(ref _current, updated);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private HubSetupDraft PreservePasswordAndNormalize(
        HubSetupDraft draft,
        HubRuntimeConfiguration current)
    {
        var password = draft.Database.Password;
        if (string.IsNullOrEmpty(password) && draft.Database.HasStoredPassword)
        {
            password = current.Draft.Database.Password;
        }
        var saved = draft with
        {
            Database = draft.Database with
            {
                Password = password,
                HasStoredPassword = !string.IsNullOrEmpty(password),
            },
        };
        return NormalizeSqlitePath(saved, Path.Combine(RootPath, "data", "sentricam.db"));
    }

    private void ApplyConfiguration(IConfigurationManager configuration)
    {
        var current = Current;
        var values = new Dictionary<string, string?>
        {
            ["Database:Provider"] = current.IsConfigured
                ? current.Draft.Database.Provider
                : HubDatabaseProviders.Sqlite,
            ["ConnectionStrings:SentriCam"] = current.IsConfigured
                ? current.DatabaseConnectionString
                : SqliteConnectionString(Path.Combine(RootPath, "data", "sentricam.db")),
            ["Authentication:Jwt:Issuer"] = configuration["Authentication:Jwt:Issuer"]
                ?? "SentriCam.LocalHub",
            ["Authentication:Jwt:Audience"] = configuration["Authentication:Jwt:Audience"]
                ?? "SentriCam.LocalClients",
            ["Authentication:Jwt:SigningKey"] = current.JwtSigningKey,
            ["RecordingStorage:RootPath"] = current.Draft.RecordingFolder,
        };
        foreach (var value in values)
        {
            configuration[value.Key] = value.Value;
        }
    }

    private async Task PersistAsync(
        HubRuntimeConfiguration configuration,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(RootPath);
        var publicDraft = configuration.Draft with
        {
            Database = configuration.Draft.Database with
            {
                Password = null,
                HasStoredPassword = !string.IsNullOrEmpty(configuration.Draft.Database.Password),
            },
        };

        // Secrets are committed first. The public state file is the completion marker,
        // so a partially failed write can never expose Completed without its secrets.
        await WriteAtomicAsync(
            _secretsPath,
            new HubSecretsDocument(
                configuration.JwtSigningKey,
                configuration.HubSecret,
                configuration.CertificatePassword,
                configuration.DatabaseConnectionString,
                configuration.Draft.Database.Password),
            cancellationToken);
        RestrictPermissions(_secretsPath);
        await WriteAtomicAsync(
            _settingsPath,
            new HubSettingsDocument(
                configuration.State,
                null,
                publicDraft,
                configuration.ConfiguredAtUtc,
                configuration.Failure),
            cancellationToken);
    }

    private static string ResolvePersistedState(HubSettingsDocument? persisted)
    {
        if (persisted is null)
        {
            return HubSetupStates.NotStarted;
        }
        if (persisted.State is not null && HubSetupStates.All.Contains(persisted.State))
        {
            return persisted.State;
        }
        return persisted.IsConfigured == true
            ? HubSetupStates.Completed
            : HubSetupStates.InProgress;
    }

    private static bool IsValidCompletion(
        HubSetupDraft draft,
        DateTimeOffset? configuredAtUtc,
        string databaseConnectionString,
        string jwtSigningKey,
        string hubSecret,
        string certificatePassword) =>
        configuredAtUtc is not null
        && !string.IsNullOrWhiteSpace(databaseConnectionString)
        && jwtSigningKey.Length >= 32
        && !string.IsNullOrWhiteSpace(hubSecret)
        && !string.IsNullOrWhiteSpace(certificatePassword)
        && HubModes.All.Contains(draft.Mode)
        && !string.IsNullOrWhiteSpace(draft.HubName)
        && !string.IsNullOrWhiteSpace(draft.RecordingFolder)
        && HubDatabaseProviders.All.Contains(draft.Database.Provider)
        && (!draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase)
            || draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        && (!draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase)
            || !string.IsNullOrWhiteSpace(draft.Database.DatabaseName))
        && (draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase)
            || (!string.IsNullOrWhiteSpace(draft.Database.Host)
                && !string.IsNullOrWhiteSpace(draft.Database.DatabaseName)))
        && HasProviderSpecificConnectionString(
            draft.Database.Provider,
            databaseConnectionString);

    private static bool HasProviderSpecificConnectionString(string provider, string connectionString)
    {
        try
        {
            if (provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
            {
                var builder = new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(connectionString);
                return !string.IsNullOrWhiteSpace(builder.DataSource);
            }
            if (provider.Equals(HubDatabaseProviders.SqlServer, StringComparison.OrdinalIgnoreCase))
            {
                var builder = new SqlConnectionStringBuilder(connectionString);
                return !string.IsNullOrWhiteSpace(builder.DataSource)
                    && !string.IsNullOrWhiteSpace(builder.InitialCatalog);
            }
            if (provider.Equals(HubDatabaseProviders.PostgreSql, StringComparison.OrdinalIgnoreCase))
            {
                var builder = new NpgsqlConnectionStringBuilder(connectionString);
                return !string.IsNullOrWhiteSpace(builder.Host)
                    && !string.IsNullOrWhiteSpace(builder.Database);
            }
            return false;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    private static bool TryReadLegacyConfiguration(
        IConfiguration configuration,
        HubSetupDraft defaultDraft,
        DateTimeOffset now,
        out HubSetupDraft draft,
        out string connectionString,
        out string signingKey,
        out DateTimeOffset? configuredAtUtc,
        out HubSetupFailure? failure)
    {
        connectionString = NullIfWhiteSpace(configuration.GetConnectionString("SentriCam")) ?? string.Empty;
        signingKey = NullIfWhiteSpace(configuration["Authentication:Jwt:SigningKey"]) ?? string.Empty;
        var provider = NullIfWhiteSpace(configuration["Database:Provider"])
            ?? HubDatabaseProviders.SqlServer;
        configuredAtUtc = DateTimeOffset.TryParse(
            configuration["SentriCam:LegacyConfiguredAtUtc"],
            out var parsedTimestamp)
            ? parsedTimestamp
            : now;
        failure = null;
        draft = defaultDraft;

        if (string.IsNullOrWhiteSpace(connectionString)
            || signingKey.Length < 32
            || !HubDatabaseProviders.All.Contains(provider))
        {
            failure = RepairFailure(
                "invalid_legacy_configuration",
                "The opted-in legacy development configuration is incomplete. Correct it or start an isolated setup profile.",
                now);
            return false;
        }

        try
        {
            HubDatabaseSettings database;
            string mode;
            if (provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
            {
                var builder = new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(connectionString);
                if (string.IsNullOrWhiteSpace(builder.DataSource))
                {
                    throw new InvalidOperationException("SQLite Data Source is required.");
                }
                database = new HubDatabaseSettings(
                    HubDatabaseProviders.Sqlite,
                    DatabaseName: Path.GetFullPath(builder.DataSource));
                mode = HubModes.Home;
            }
            else if (provider.Equals(HubDatabaseProviders.SqlServer, StringComparison.OrdinalIgnoreCase))
            {
                var builder = new SqlConnectionStringBuilder(connectionString);
                if (string.IsNullOrWhiteSpace(builder.DataSource)
                    || string.IsNullOrWhiteSpace(builder.InitialCatalog))
                {
                    throw new InvalidOperationException("SQL Server Data Source and database are required.");
                }
                database = new HubDatabaseSettings(
                    HubDatabaseProviders.SqlServer,
                    Host: builder.DataSource,
                    DatabaseName: builder.InitialCatalog,
                    Username: NullIfWhiteSpace(builder.UserID),
                    Password: NullIfWhiteSpace(builder.Password),
                    TrustServerCertificate: builder.TrustServerCertificate,
                    UseIntegratedSecurity: builder.IntegratedSecurity,
                    HasStoredPassword: !string.IsNullOrWhiteSpace(builder.Password));
                mode = HubModes.Enterprise;
            }
            else
            {
                var builder = new NpgsqlConnectionStringBuilder(connectionString);
                if (string.IsNullOrWhiteSpace(builder.Host)
                    || string.IsNullOrWhiteSpace(builder.Database))
                {
                    throw new InvalidOperationException("PostgreSQL host and database are required.");
                }
                database = new HubDatabaseSettings(
                    HubDatabaseProviders.PostgreSql,
                    Host: builder.Host,
                    Port: builder.Port,
                    DatabaseName: builder.Database,
                    Username: NullIfWhiteSpace(builder.Username),
                    Password: NullIfWhiteSpace(builder.Password),
                    Encrypt: builder.SslMode != SslMode.Disable,
                    HasStoredPassword: !string.IsNullOrWhiteSpace(builder.Password));
                mode = HubModes.Enterprise;
            }

            draft = defaultDraft with
            {
                Mode = mode,
                RecordingFolder = NullIfWhiteSpace(configuration["RecordingStorage:RootPath"])
                    ?? defaultDraft.RecordingFolder,
                Database = database,
                CurrentStep = "ready",
            };
            return true;
        }
        catch (Exception exception) when (exception is ArgumentException or InvalidOperationException)
        {
            failure = RepairFailure(
                "invalid_legacy_configuration",
                "The opted-in legacy development database settings are invalid. Review them before starting the Hub.",
                now);
            configuredAtUtc = null;
            return false;
        }
    }

    private static void ValidateFreshInstallProfile(
        string root,
        string? configuredRoot,
        bool freshInstall,
        bool isDevelopment)
    {
        if (!freshInstall)
        {
            return;
        }
        if (!isDevelopment)
        {
            throw new InvalidOperationException("Fresh-install simulation is available only in Development.");
        }
        if (configuredRoot is null)
        {
            throw new InvalidOperationException("Fresh-install simulation requires an isolated SentriCam:Home directory.");
        }
        var relative = Path.GetRelativePath(Path.GetFullPath(Path.GetTempPath()), root);
        if (relative.Equals("..", StringComparison.Ordinal)
            || relative.StartsWith($"..{Path.DirectorySeparatorChar}", StringComparison.Ordinal)
            || Path.IsPathRooted(relative))
        {
            throw new InvalidOperationException("Fresh-install simulation must use an isolated temporary directory.");
        }
        if (Directory.Exists(root) && Directory.EnumerateFileSystemEntries(root).Any())
        {
            throw new InvalidOperationException("Fresh-install simulation requires a new empty temporary directory.");
        }
    }

    private static HubSetupDraft CreateDefaultDraft(string databasePath)
    {
        var defaultFolder = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.MyVideos),
            "SentriCam");
        return new HubSetupDraft(
            HubModes.Home,
            DefaultHubName(),
            defaultFolder,
            HubStoragePolicies.Balanced,
            RetentionDays: 30,
            MinimumFreeSpaceGb: 10,
            StartAutomatically: true,
            OpenDashboard: true,
            new HubDatabaseSettings(
                HubDatabaseProviders.Sqlite,
                DatabaseName: databasePath),
            new HubNetworkSettings(),
            new HubMediaSettings(),
            CurrentStep: "welcome");
    }

    private static HubSetupDraft NormalizeSqlitePath(HubSetupDraft draft, string databasePath) =>
        draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase)
            ? draft with
            {
                Database = draft.Database with
                {
                    Host = null,
                    Port = null,
                    DatabaseName = databasePath,
                    Username = null,
                    Password = null,
                    HasStoredPassword = false,
                },
            }
            : draft;

    private static HubSetupDraft UseHomeDatabase(HubSetupDraft draft, string databasePath) => draft with
    {
        Database = new HubDatabaseSettings(
            HubDatabaseProviders.Sqlite,
            DatabaseName: databasePath),
    };

    private static HubSetupFailure RepairFailure(
        string code,
        string message,
        DateTimeOffset timestamp) => new(code, message, timestamp);

    private static string SqliteConnectionString(string databasePath) =>
        $"Data Source={databasePath};Cache=Shared;Foreign Keys=True";

    private static async Task WriteAtomicAsync<T>(
        string path,
        T value,
        CancellationToken cancellationToken)
    {
        var temporary = $"{path}.{Guid.NewGuid():N}.tmp";
        await using (var stream = new FileStream(
            temporary,
            FileMode.CreateNew,
            FileAccess.Write,
            FileShare.None,
            16 * 1024,
            FileOptions.Asynchronous))
        {
            await JsonSerializer.SerializeAsync(stream, value, JsonOptions, cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }
        File.Move(temporary, path, overwrite: true);
    }

    private static T? Read<T>(string path)
    {
        if (!File.Exists(path))
        {
            return default;
        }
        try
        {
            return JsonSerializer.Deserialize<T>(File.ReadAllText(path), JsonOptions);
        }
        catch (JsonException)
        {
            return default;
        }
    }

    private static void RestrictPermissions(string path)
    {
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
    }

    private static string DefaultHubName()
    {
        var machineName = Environment.MachineName.Trim();
        return string.IsNullOrWhiteSpace(machineName)
            ? "My SentriCam Hub"
            : $"{machineName} Hub";
    }

    public void Dispose()
    {
        _writeLock.Dispose();
    }

    private static string? NullIfWhiteSpace(string? value) =>
        string.IsNullOrWhiteSpace(value) ? null : value;

    private sealed record HubSettingsDocument(
        string? State,
        bool? IsConfigured,
        HubSetupDraft? Draft,
        DateTimeOffset? ConfiguredAtUtc,
        HubSetupFailure? Failure);

    private sealed record HubSecretsDocument(
        string JwtSigningKey,
        string HubSecret,
        string CertificatePassword,
        string DatabaseConnectionString,
        string? DatabasePassword);
}
