using System.Text.Json;
using Microsoft.Extensions.Configuration;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Hub;

namespace SentriCam.Tests.Infrastructure;

public sealed class HubSetupStateStoreTests
{
    [Fact]
    public void FreshInstallStartsUnconfiguredWithHomeSqliteDefaults()
    {
        var root = TemporaryFolder();
        try
        {
            using var store = Bootstrap(root);

            Assert.Equal(HubSetupStates.NotStarted, store.Current.State);
            Assert.False(store.Current.IsConfigured);
            Assert.Null(store.Current.ConfiguredAtUtc);
            Assert.Equal(HubModes.Home, store.Current.Draft.Mode);
            Assert.Equal(HubDatabaseProviders.Sqlite, store.Current.Draft.Database.Provider);
            Assert.Equal(
                Path.GetFullPath(Path.Combine(root, "data", "sentricam.db")),
                Path.GetFullPath(store.Current.Draft.Database.DatabaseName!));
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task DraftExistenceMeansInProgressRatherThanConfigured()
    {
        var root = TemporaryFolder();
        try
        {
            using (var store = Bootstrap(root))
            {
                await store.SaveDraftAsync(
                    store.Current.Draft with { CurrentStep = "recording-folder" },
                    TestContext.Current.CancellationToken);

                Assert.Equal(HubSetupStates.InProgress, store.Current.State);
                Assert.False(store.Current.IsConfigured);
                Assert.Null(store.Current.ConfiguredAtUtc);
            }

            using var restored = Bootstrap(root);
            Assert.Equal(HubSetupStates.InProgress, restored.Current.State);
            Assert.Equal("recording-folder", restored.Current.Draft.CurrentStep);
            Assert.False(restored.Current.IsConfigured);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task BuiltInSqlitePathIsPinnedUnderHubHome()
    {
        var root = TemporaryFolder();
        try
        {
            using var store = Bootstrap(root);
            await store.SaveDraftAsync(
                store.Current.Draft with
                {
                    Database = store.Current.Draft.Database with
                    {
                        Host = "must-not-survive",
                        Port = 1433,
                        DatabaseName = Path.Combine(Path.GetTempPath(), "outside-hub.db"),
                        Username = "must-not-survive",
                        Password = "must-not-survive",
                    },
                },
                TestContext.Current.CancellationToken);

            Assert.Equal(
                Path.GetFullPath(Path.Combine(root, "data", "sentricam.db")),
                Path.GetFullPath(store.Current.Draft.Database.DatabaseName!));
            Assert.Null(store.Current.Draft.Database.Host);
            Assert.Null(store.Current.Draft.Database.Port);
            Assert.Null(store.Current.Draft.Database.Username);
            Assert.Null(store.Current.Draft.Database.Password);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task CompletionRequiresAndPersistsConfiguredTimestamp()
    {
        var root = TemporaryFolder();
        var configuredAt = new DateTimeOffset(2026, 8, 1, 10, 0, 0, TimeSpan.Zero);
        try
        {
            using (var store = Bootstrap(root, new FixedTimeProvider(configuredAt)))
            {
                await store.CompleteAsync(
                    store.Current.Draft with { CurrentStep = "ready" },
                    $"Data Source={Path.Combine(root, "data", "sentricam.db")};Foreign Keys=True",
                    TestContext.Current.CancellationToken);

                Assert.Equal(HubSetupStates.Completed, store.Current.State);
                Assert.True(store.Current.IsConfigured);
                Assert.Equal(configuredAt, store.Current.ConfiguredAtUtc);
            }

            using var restored = Bootstrap(root);
            Assert.Equal(HubSetupStates.Completed, restored.Current.State);
            Assert.True(restored.Current.IsConfigured);
            Assert.Equal(configuredAt, restored.Current.ConfiguredAtUtc);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void CompletedRuntimeWithoutTimestampIsNotConfigured()
    {
        var runtime = new HubRuntimeConfiguration(
            HubSetupStates.Completed,
            Draft("/tmp/recordings", "/tmp/sentricam.db"),
            "Data Source=/tmp/sentricam.db",
            Convert.ToBase64String(new byte[64]),
            Convert.ToBase64String(new byte[32]),
            Convert.ToBase64String(new byte[32]),
            null);

        Assert.False(runtime.IsConfigured);
    }

    [Fact]
    public async Task LegacyTrueWithoutTimestampBecomesRepairAndNormalizesHomeDatabase()
    {
        var root = TemporaryFolder();
        try
        {
            var invalidDraft = Draft(Path.Combine(root, "recordings"), Path.Combine(root, "sentricam.db")) with
            {
                Database = new HubDatabaseSettings(
                    HubDatabaseProviders.SqlServer,
                    DatabaseName: Path.Combine(root, "sentricam.db")),
            };
            await File.WriteAllTextAsync(
                Path.Combine(root, "hub-settings.json"),
                JsonSerializer.Serialize(new
                {
                    isConfigured = true,
                    draft = invalidDraft,
                    configuredAtUtc = (DateTimeOffset?)null,
                }),
                TestContext.Current.CancellationToken);

            using var store = Bootstrap(root);

            Assert.Equal(HubSetupStates.NeedsRepair, store.Current.State);
            Assert.False(store.Current.IsConfigured);
            Assert.Null(store.Current.ConfiguredAtUtc);
            Assert.Equal(HubDatabaseProviders.Sqlite, store.Current.Draft.Database.Provider);
            Assert.NotNull(store.Current.Failure);
            Assert.Equal("mixed_home_database", store.Current.Failure!.Code);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void ValidOptedInSqlServerDevelopmentConfigurationRemainsSupported()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager
            {
                ["SentriCam:Home"] = root,
                ["SentriCam:UseLegacyConfiguration"] = "true",
                ["Database:Provider"] = HubDatabaseProviders.SqlServer,
                ["ConnectionStrings:SentriCam"] =
                    "Server=localhost;Database=sentricam;Integrated Security=True;TrustServerCertificate=True",
                ["Authentication:Jwt:SigningKey"] =
                    "test-only-signing-key-with-at-least-thirty-two-chars",
            };

            using var store = FileHubSetupStore.Bootstrap(configuration, root);

            Assert.True(store.Current.IsConfigured);
            Assert.Equal(HubSetupStates.Completed, store.Current.State);
            Assert.NotNull(store.Current.ConfiguredAtUtc);
            Assert.Equal(HubModes.Enterprise, store.Current.Draft.Mode);
            Assert.Equal(HubDatabaseProviders.SqlServer, store.Current.Draft.Database.Provider);
            Assert.Equal("localhost", store.Current.Draft.Database.Host);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void AmbientLegacyValuesDoNotPromoteAFreshHubOrChangeHomeProvider()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager
            {
                ["SentriCam:Home"] = root,
                ["ConnectionStrings:SentriCam"] =
                    "Server=localhost;Database=sentricam;Integrated Security=True;TrustServerCertificate=True",
                ["Authentication:Jwt:SigningKey"] =
                    "test-only-signing-key-with-at-least-thirty-two-chars",
            };

            using var store = FileHubSetupStore.Bootstrap(configuration, root);

            Assert.Equal(HubSetupStates.NotStarted, store.Current.State);
            Assert.False(store.Current.IsConfigured);
            Assert.Null(store.Current.ConfiguredAtUtc);
            Assert.Equal(HubModes.Home, store.Current.Draft.Mode);
            Assert.Equal(HubDatabaseProviders.Sqlite, store.Current.Draft.Database.Provider);
            Assert.Contains("Data Source=", store.Current.DatabaseConnectionString, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void DevelopmentFreshInstallProfileIsIsolatedFromExistingHubData()
    {
        var existingRoot = TemporaryFolder();
        var freshRoot = TemporaryFolder();
        var marker = Path.Combine(existingRoot, "existing-hub.marker");
        try
        {
            File.WriteAllText(marker, "preserve");
            var configuration = new ConfigurationManager
            {
                ["SentriCam:Home"] = freshRoot,
                ["SentriCam:FreshInstall"] = "true",
                ["SentriCam:UseLegacyConfiguration"] = "true",
                ["ConnectionStrings:SentriCam"] = "Server=legacy;Database=legacy",
                ["Authentication:Jwt:SigningKey"] =
                    "test-only-signing-key-with-at-least-thirty-two-chars",
            };

            using var store = FileHubSetupStore.Bootstrap(
                configuration,
                existingRoot,
                isDevelopment: true);

            Assert.Equal(Path.GetFullPath(freshRoot), store.RootPath);
            Assert.Equal(HubSetupStates.NotStarted, store.Current.State);
            Assert.Equal(HubDatabaseProviders.Sqlite, store.Current.Draft.Database.Provider);
            Assert.Equal("preserve", File.ReadAllText(marker));
        }
        finally
        {
            Directory.Delete(existingRoot, recursive: true);
            Directory.Delete(freshRoot, recursive: true);
        }
    }

    [Fact]
    public void ProductionRejectsFreshInstallSimulation()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager
            {
                ["SentriCam:Home"] = root,
                ["SentriCam:FreshInstall"] = "true",
            };

            var exception = Assert.Throws<InvalidOperationException>(() =>
                FileHubSetupStore.Bootstrap(configuration, root, isDevelopment: false));

            Assert.Contains("only in Development", exception.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    private static FileHubSetupStore Bootstrap(string root, TimeProvider? timeProvider = null)
    {
        var configuration = new ConfigurationManager
        {
            ["SentriCam:Home"] = root,
        };
        return FileHubSetupStore.Bootstrap(configuration, root, timeProvider: timeProvider);
    }

    private static HubSetupDraft Draft(string recordingFolder, string databasePath) => new(
        HubModes.Home,
        "Test Home",
        recordingFolder,
        HubStoragePolicies.Balanced,
        30,
        10,
        true,
        true,
        new HubDatabaseSettings(HubDatabaseProviders.Sqlite, DatabaseName: databasePath),
        new HubNetworkSettings(),
        new HubMediaSettings());

    private static string TemporaryFolder()
    {
        var root = Path.Combine(Path.GetTempPath(), $"sentricam-setup-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        return root;
    }

    private sealed class FixedTimeProvider(DateTimeOffset timestamp) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => timestamp;
    }
}
