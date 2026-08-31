using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;
using Microsoft.Extensions.Configuration;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Hub;
using SentriCam.Infrastructure.Persistence;

namespace SentriCam.Tests.Infrastructure;

public sealed class HubDatabaseProviderTests
{
    [Theory]
    [InlineData(
        HubDatabaseProviders.Sqlite,
        "Data Source=sentricam.db",
        "Microsoft.EntityFrameworkCore.Sqlite",
        "SentriCam.Migrations.Sqlite")]
    [InlineData(
        HubDatabaseProviders.SqlServer,
        "Server=localhost;Database=sentricam;Integrated Security=True;TrustServerCertificate=True",
        "Microsoft.EntityFrameworkCore.SqlServer",
        "SentriCam.Infrastructure")]
    [InlineData(
        HubDatabaseProviders.PostgreSql,
        "Host=localhost;Database=sentricam;Username=hub;Password=test-only",
        "Npgsql.EntityFrameworkCore.PostgreSQL",
        "SentriCam.Migrations.PostgreSql")]
    public void ProviderSelectorUsesTheExpectedAdapterAndMigrationAssembly(
        string provider,
        string connectionString,
        string expectedAdapter,
        string expectedMigrationAssembly)
    {
        var options = new DbContextOptionsBuilder<SentriCamDbContext>()
            .ConfigureSentriCamProvider(provider, connectionString)
            .Options;

        using var context = new SentriCamDbContext(options);

        Assert.Equal(expectedAdapter, context.Database.ProviderName);
        Assert.Equal(
            expectedMigrationAssembly,
            context.GetService<IMigrationsAssembly>().Assembly.GetName().Name);
    }

    [Fact]
    public async Task SqliteProviderAppliesItsOwnMigrationsAndCreatesThePlatformModel()
    {
        var root = TemporaryFolder();
        try
        {
            var databasePath = Path.Combine(root, "sentricam.db");
            var options = new DbContextOptionsBuilder<SentriCamDbContext>()
                .ConfigureSentriCamProvider(
                    HubDatabaseProviders.Sqlite,
                    $"Data Source={databasePath};Foreign Keys=True")
                .Options;

            await using (var context = new SentriCamDbContext(options))
            {
                await context.Database.MigrateAsync(TestContext.Current.CancellationToken);
                Assert.Equal("Microsoft.EntityFrameworkCore.Sqlite", context.Database.ProviderName);
            }

            await using var verification = new SentriCamDbContext(options);
            var tables = await verification.Database
                .SqlQueryRaw<string>("SELECT name AS Value FROM sqlite_master WHERE type = 'table'")
                .ToListAsync(TestContext.Current.CancellationToken);
            Assert.Contains("Devices", tables);
            Assert.Contains("Recordings", tables);
            Assert.Contains("__EFMigrationsHistory", tables);

            var utcNow = DateTimeOffset.UtcNow;
            var expiringCommands = await verification.DeviceCommands
                .Where(command => command.ExpiresAtUtc <= utcNow)
                .ToArrayAsync(TestContext.Current.CancellationToken);
            var pendingThumbnails = await verification.Recordings
                .Where(recording => recording.ThumbnailProcessingStartedUtc < utcNow)
                .OrderBy(recording => recording.UploadedUtc)
                .ToArrayAsync(TestContext.Current.CancellationToken);
            Assert.Empty(expiringCommands);
            Assert.Empty(pendingThumbnails);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task SetupStoreKeepsGeneratedAndExternalSecretsOutOfPublicSettings()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager
            {
                ["SentriCam:Home"] = root,
            };
            using var store = FileHubSetupStore.Bootstrap(configuration, root);
            var draft = store.Current.Draft with
            {
                Mode = HubModes.Enterprise,
                Database = store.Current.Draft.Database with
                {
                    Provider = HubDatabaseProviders.PostgreSql,
                    Host = "db.internal",
                    DatabaseName = "sentricam",
                    Username = "hub",
                    Password = "test-password-that-must-stay-secret",
                },
            };

            await store.CompleteAsync(
                draft,
                "Host=db.internal;Database=sentricam;Username=hub;Password=test-password-that-must-stay-secret",
                TestContext.Current.CancellationToken);

            var settings = await File.ReadAllTextAsync(
                Path.Combine(root, "hub-settings.json"),
                TestContext.Current.CancellationToken);
            var secrets = await File.ReadAllTextAsync(
                Path.Combine(root, "hub-secrets.json"),
                TestContext.Current.CancellationToken);
            Assert.DoesNotContain("test-password-that-must-stay-secret", settings, StringComparison.Ordinal);
            Assert.DoesNotContain(store.Current.JwtSigningKey, settings, StringComparison.Ordinal);
            Assert.DoesNotContain("isConfigured", settings, StringComparison.OrdinalIgnoreCase);
            Assert.Contains($"\"state\": \"{HubSetupStates.Completed}\"", settings, StringComparison.Ordinal);
            Assert.Contains("test-password-that-must-stay-secret", secrets, StringComparison.Ordinal);
            Assert.True(store.Current.JwtSigningKey.Length >= 32);
            Assert.False(string.IsNullOrWhiteSpace(store.Current.HubSecret));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    private static string TemporaryFolder()
    {
        var root = Path.Combine(Path.GetTempPath(), $"sentricam-hub-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        return root;
    }
}
