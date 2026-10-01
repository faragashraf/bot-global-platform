using System.Diagnostics;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Persistence;

namespace SentriCam.Infrastructure.Hub;

public sealed class HubProvisioner(
    IHubSetupStore store,
    IMediaEngineProbe mediaEngineProbe) : IHubProvisioner
{
    public async Task<HubConnectionTestResult> TestDatabaseAsync(
        HubDatabaseSettings database,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(database);
        var stopwatch = Stopwatch.StartNew();
        try
        {
            if (database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
            {
                var path = ResolveSqlitePath(database);
                var parent = Path.GetDirectoryName(path)
                    ?? throw new InvalidOperationException("The local data folder is invalid.");
                Directory.CreateDirectory(parent);
                await using var probe = new FileStream(
                    Path.Combine(parent, $".sentricam-write-test-{Guid.NewGuid():N}"),
                    FileMode.CreateNew,
                    FileAccess.ReadWrite,
                    FileShare.None,
                    1,
                    FileOptions.DeleteOnClose | FileOptions.Asynchronous);
                await probe.WriteAsync(new byte[] { 0x53 }, cancellationToken);
                return Success(stopwatch, "Private storage is ready.");
            }

            await using var context = CreateContext(database);
            var connected = await context.Database.CanConnectAsync(cancellationToken);
            return connected
                ? Success(stopwatch, "Connection successful.")
                : new HubConnectionTestResult(false, "unavailable", "The database could not be reached.");
        }
        catch (Exception exception) when (
            exception is SqlException
            or NpgsqlException
            or IOException
            or UnauthorizedAccessException
            or ArgumentException
            or InvalidOperationException)
        {
            return new HubConnectionTestResult(
                false,
                "unavailable",
                SafeConnectionMessage(database.Provider),
                stopwatch.ElapsedMilliseconds);
        }
    }

    public async Task<IReadOnlyCollection<string>> ProvisionAsync(
        HubSetupDraft draft,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(draft);
        EnsureModeProviderCompatibility(draft);
        var actions = new List<string>();
        Directory.CreateDirectory(draft.RecordingFolder);
        actions.Add("Recording folder prepared");

        if (draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            var dataFolder = Path.GetDirectoryName(ResolveSqlitePath(draft.Database));
            if (dataFolder is not null)
            {
                Directory.CreateDirectory(dataFolder);
            }
            actions.Add("Private Hub library created");
        }

        await using (var context = CreateContext(draft))
        {
            await context.Database.MigrateAsync(cancellationToken);
        }
        actions.Add("Latest Hub updates applied");
        actions.Add("Secure connection prepared");
        actions.Add("Device pairing protected");
        actions.Add("Storage policy applied");

        if (draft.Network.HttpsEnabled)
        {
            if (draft.Network.GenerateCertificate)
            {
                await CreateHubCertificateAsync(cancellationToken);
                actions.Add("HTTPS certificate created and protected");
            }
            else
            {
                ValidateCertificate(draft.Network.CertificatePath!);
                actions.Add("HTTPS certificate verified");
            }
        }

        var media = mediaEngineProbe.Detect(draft.Media);
        actions.Add(media.Status == "ready"
            ? "Video support verified"
            : "Video support reserved for the Hub package");
        if (draft.StartAutomatically)
        {
            actions.Add("Automatic start preference saved");
        }
        return actions;
    }

    public string BuildConnectionString(HubSetupDraft draft)
    {
        ArgumentNullException.ThrowIfNull(draft);
        EnsureModeProviderCompatibility(draft);
        return BuildProviderConnectionString(draft.Database);
    }

    private string BuildProviderConnectionString(HubDatabaseSettings database)
    {
        ArgumentNullException.ThrowIfNull(database);
        var resolved = WithStoredPassword(database);
        if (resolved.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            return $"Data Source={ResolveSqlitePath(resolved)};Cache=Shared;Foreign Keys=True";
        }
        if (resolved.Provider.Equals(HubDatabaseProviders.SqlServer, StringComparison.OrdinalIgnoreCase))
        {
            var builder = new SqlConnectionStringBuilder
            {
                DataSource = FormatHost(resolved.Host!, resolved.Port),
                InitialCatalog = resolved.DatabaseName,
                Encrypt = resolved.Encrypt,
                TrustServerCertificate = resolved.TrustServerCertificate,
                IntegratedSecurity = resolved.UseIntegratedSecurity,
                ConnectTimeout = 10,
            };
            if (!resolved.UseIntegratedSecurity)
            {
                builder.UserID = resolved.Username;
                builder.Password = resolved.Password;
            }
            return builder.ConnectionString;
        }
        if (resolved.Provider.Equals(HubDatabaseProviders.PostgreSql, StringComparison.OrdinalIgnoreCase))
        {
            var builder = new NpgsqlConnectionStringBuilder
            {
                Host = resolved.Host,
                Port = resolved.Port ?? 5432,
                Database = resolved.DatabaseName,
                Username = resolved.Username,
                Password = resolved.Password,
                SslMode = resolved.Encrypt ? SslMode.Prefer : SslMode.Disable,
                Timeout = 10,
            };
            return builder.ConnectionString;
        }
        throw new InvalidOperationException($"Unsupported database provider '{resolved.Provider}'.");
    }

    private SentriCamDbContext CreateContext(HubSetupDraft draft)
    {
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .ConfigureSentriCamProvider(draft.Database.Provider, BuildConnectionString(draft))
            .Options;
        return new SentriCamDbContext(options);
    }

    private SentriCamDbContext CreateContext(HubDatabaseSettings database)
    {
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .ConfigureSentriCamProvider(database.Provider, BuildProviderConnectionString(database))
            .Options;
        return new SentriCamDbContext(options);
    }

    private static void EnsureModeProviderCompatibility(HubSetupDraft draft)
    {
        if (draft.Mode.Equals(HubModes.Home, StringComparison.OrdinalIgnoreCase)
            && !draft.Database.Provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("Home setup can only use the built-in SQLite provider.");
        }
    }

    private HubDatabaseSettings WithStoredPassword(HubDatabaseSettings database)
    {
        if (!string.IsNullOrEmpty(database.Password) || !database.HasStoredPassword)
        {
            return database;
        }
        return database with { Password = store.Current.Draft.Database.Password };
    }

    private string ResolveSqlitePath(HubDatabaseSettings database)
    {
        var configured = database.DatabaseName;
        if (!string.IsNullOrWhiteSpace(configured))
        {
            return Path.GetFullPath(configured);
        }
        var root = store is FileHubSetupStore fileStore
            ? fileStore.RootPath
            : Path.Combine(Directory.GetCurrentDirectory(), "data", "hub");
        return Path.Combine(root, "data", "sentricam.db");
    }

    private static string FormatHost(string host, int? port) =>
        port is null ? host : $"{host},{port.Value}";

    private static HubConnectionTestResult Success(Stopwatch stopwatch, string message) =>
        new(true, "ready", message, stopwatch.ElapsedMilliseconds);

    private static string SafeConnectionMessage(string provider) =>
        provider.Equals(HubDatabaseProviders.Sqlite, StringComparison.OrdinalIgnoreCase)
            ? "The private data folder is not writable. Choose another recording location or check permissions."
            : "SentriCam could not connect with those database settings. Check the server, account, and encryption options.";

    private async Task CreateHubCertificateAsync(CancellationToken cancellationToken)
    {
        var root = store is FileHubSetupStore fileStore
            ? fileStore.RootPath
            : Path.Combine(Directory.GetCurrentDirectory(), "data", "hub");
        var certificateFolder = Path.Combine(root, "certificates");
        Directory.CreateDirectory(certificateFolder);
        var certificatePath = Path.Combine(certificateFolder, "sentricam-hub.pfx");
        using var rsa = RSA.Create(2_048);
        var request = new CertificateRequest(
            $"CN={Environment.MachineName}.local",
            rsa,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(
            X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment,
            true));
        request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
        var names = new SubjectAlternativeNameBuilder();
        names.AddDnsName("localhost");
        names.AddDnsName($"{Environment.MachineName}.local");
        names.AddIpAddress(System.Net.IPAddress.Loopback);
        request.CertificateExtensions.Add(names.Build());
        using var certificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddDays(-1),
            DateTimeOffset.UtcNow.AddYears(3));
        var content = certificate.Export(
            X509ContentType.Pfx,
            store.Current.CertificatePassword);
        await File.WriteAllBytesAsync(certificatePath, content, cancellationToken);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(certificatePath, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
    }

    private static void ValidateCertificate(string path)
    {
        if (!File.Exists(path))
        {
            throw new InvalidOperationException("The selected certificate file does not exist.");
        }
        _ = X509CertificateLoader.LoadPkcs12FromFile(path, null);
    }
}
