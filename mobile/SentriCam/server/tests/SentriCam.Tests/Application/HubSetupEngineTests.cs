using FluentValidation;
using Microsoft.Extensions.Configuration;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;
using SentriCam.Infrastructure.Hub;

namespace SentriCam.Tests.Application;

public sealed class HubSetupEngineTests
{
    [Fact]
    public async Task FailedProvisioningPersistsFailedUnconfiguredState()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager { ["SentriCam:Home"] = root };
            using var store = FileHubSetupStore.Bootstrap(configuration, root);
            var provisioner = new RecordingProvisioner { ProvisioningFailure = new IOException("test") };
            var engine = new HubSetupEngine(store, provisioner, new HubSetupDraftValidator());

            await Assert.ThrowsAsync<IOException>(() =>
                engine.ConfigureAsync(store.Current.Draft, TestContext.Current.CancellationToken));

            Assert.Equal(HubSetupStates.Failed, store.Current.State);
            Assert.False(store.Current.IsConfigured);
            Assert.Null(store.Current.ConfiguredAtUtc);
            Assert.Equal("provisioning_failed", store.Current.Failure?.Code);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task SuccessfulHomeConfigurationUsesOnlySqliteAndCompletesLast()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager { ["SentriCam:Home"] = root };
            using var store = FileHubSetupStore.Bootstrap(configuration, root);
            var provisioner = new RecordingProvisioner();
            var engine = new HubSetupEngine(store, provisioner, new HubSetupDraftValidator());

            var result = await engine.ConfigureAsync(
                store.Current.Draft,
                TestContext.Current.CancellationToken);

            Assert.True(result.IsConfigured);
            Assert.Equal([HubDatabaseProviders.Sqlite], provisioner.TestedProviders);
            Assert.Equal([HubDatabaseProviders.Sqlite], provisioner.ProvisionedProviders);
            Assert.Equal([HubDatabaseProviders.Sqlite], provisioner.BuiltProviders);
            Assert.True(store.Current.IsConfigured);
            Assert.NotNull(store.Current.ConfiguredAtUtc);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public async Task MixedHomeSqlServerConfigurationIsRejectedBeforeConnectionBuilding()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager { ["SentriCam:Home"] = root };
            using var store = FileHubSetupStore.Bootstrap(configuration, root);
            var provisioner = new RecordingProvisioner();
            var engine = new HubSetupEngine(store, provisioner, new HubSetupDraftValidator());
            var mixed = store.Current.Draft with
            {
                Database = new HubDatabaseSettings(
                    HubDatabaseProviders.SqlServer,
                    Host: null,
                    DatabaseName: Path.Combine(root, "sentricam.db")),
            };

            await Assert.ThrowsAsync<ValidationException>(() =>
                engine.ConfigureAsync(mixed, TestContext.Current.CancellationToken));

            Assert.Empty(provisioner.TestedProviders);
            Assert.Empty(provisioner.ProvisionedProviders);
            Assert.Empty(provisioner.BuiltProviders);
            Assert.False(store.Current.IsConfigured);
            Assert.Equal(HubSetupStates.Failed, store.Current.State);
            Assert.Equal(HubDatabaseProviders.Sqlite, store.Current.Draft.Database.Provider);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    [Fact]
    public void HomeConnectionBuilderRejectsSqlServerBeforeReadingDataSource()
    {
        var store = new StaticStore(Draft() with
        {
            Database = new HubDatabaseSettings(
                HubDatabaseProviders.SqlServer,
                Host: null,
                DatabaseName: "/tmp/sentricam.db"),
        });
        var provisioner = new HubProvisioner(store, new ReadyMediaProbe());

        var exception = Assert.Throws<InvalidOperationException>(() =>
            provisioner.BuildConnectionString(store.Current.Draft));

        Assert.Contains("Home setup", exception.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("Data Source", exception.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void AdvancedSqlServerConnectionBuilderRemainsAvailable()
    {
        var draft = Draft() with
        {
            Mode = HubModes.Enterprise,
            Database = new HubDatabaseSettings(
                HubDatabaseProviders.SqlServer,
                Host: "localhost",
                DatabaseName: "sentricam",
                UseIntegratedSecurity: true,
                TrustServerCertificate: true),
        };
        var provisioner = new HubProvisioner(new StaticStore(draft), new ReadyMediaProbe());

        var connectionString = provisioner.BuildConnectionString(draft);

        Assert.Contains("Data Source=localhost", connectionString, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("Initial Catalog=sentricam", connectionString, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task CompletedSetupStateRedactsAbsoluteAndSecretPaths()
    {
        var root = TemporaryFolder();
        try
        {
            var configuration = new ConfigurationManager { ["SentriCam:Home"] = root };
            using var store = FileHubSetupStore.Bootstrap(configuration, root);
            var recordingFolder = Path.Combine(root, "recordings");
            var completedDraft = store.Current.Draft with
            {
                Mode = HubModes.Enterprise,
                RecordingFolder = recordingFolder,
                Database = new HubDatabaseSettings(
                    HubDatabaseProviders.SqlServer,
                    Host: "database.internal",
                    Port: 1433,
                    DatabaseName: "sentricam",
                    Username: "hub-user",
                    Password: "test-password-that-must-not-return"),
                Network = store.Current.Draft.Network with
                {
                    CertificatePath = Path.Combine(root, "certificates", "hub.pfx"),
                },
                Media = store.Current.Draft.Media with
                {
                    CustomPath = Path.Combine(root, "media", "ffmpeg"),
                },
            };
            await store.CompleteAsync(
                completedDraft,
                "Server=database.internal,1433;Database=sentricam;User ID=hub-user;Password=test-password-that-must-not-return;Encrypt=True",
                TestContext.Current.CancellationToken);
            var engine = new HubSetupEngine(
                store,
                new RecordingProvisioner(),
                new HubSetupDraftValidator());

            var state = engine.GetState();

            Assert.True(state.IsConfigured);
            Assert.Equal("recordings", state.Draft.RecordingFolder);
            Assert.Null(state.Draft.Database.Host);
            Assert.Null(state.Draft.Database.Port);
            Assert.Null(state.Draft.Database.DatabaseName);
            Assert.Null(state.Draft.Database.Username);
            Assert.Null(state.Draft.Database.Password);
            Assert.False(state.Draft.Database.HasStoredPassword);
            Assert.Null(state.Draft.Network.CertificatePath);
            Assert.Null(state.Draft.Media.CustomPath);
            Assert.Equal(string.Empty, state.DefaultRecordingFolder);
        }
        finally
        {
            Directory.Delete(root, recursive: true);
        }
    }

    private static HubSetupDraft Draft() => new(
        HubModes.Home,
        "Test Home",
        "/tmp/recordings",
        HubStoragePolicies.Balanced,
        30,
        10,
        true,
        true,
        new HubDatabaseSettings(HubDatabaseProviders.Sqlite, DatabaseName: "/tmp/sentricam.db"),
        new HubNetworkSettings(),
        new HubMediaSettings());

    private static string TemporaryFolder()
    {
        var root = Path.Combine(Path.GetTempPath(), $"sentricam-engine-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(root);
        return root;
    }

    private sealed class RecordingProvisioner : IHubProvisioner
    {
        public List<string> TestedProviders { get; } = [];
        public List<string> ProvisionedProviders { get; } = [];
        public List<string> BuiltProviders { get; } = [];
        public Exception? ProvisioningFailure { get; init; }

        public Task<HubConnectionTestResult> TestDatabaseAsync(
            HubDatabaseSettings database,
            CancellationToken cancellationToken = default)
        {
            TestedProviders.Add(database.Provider);
            return Task.FromResult(new HubConnectionTestResult(true, "ready", "Ready"));
        }

        public Task<IReadOnlyCollection<string>> ProvisionAsync(
            HubSetupDraft draft,
            CancellationToken cancellationToken = default)
        {
            ProvisionedProviders.Add(draft.Database.Provider);
            return ProvisioningFailure is null
                ? Task.FromResult((IReadOnlyCollection<string>)["Configured"])
                : Task.FromException<IReadOnlyCollection<string>>(ProvisioningFailure);
        }

        public string BuildConnectionString(HubSetupDraft draft)
        {
            BuiltProviders.Add(draft.Database.Provider);
            return $"Data Source={draft.Database.DatabaseName};Foreign Keys=True";
        }
    }

    private sealed class StaticStore(HubSetupDraft draft) : IHubSetupStore
    {
        public HubRuntimeConfiguration Current { get; } = new(
            HubSetupStates.InProgress,
            draft,
            "Data Source=/tmp/sentricam.db",
            Convert.ToBase64String(new byte[64]),
            Convert.ToBase64String(new byte[32]),
            Convert.ToBase64String(new byte[32]),
            null);

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

    private sealed class ReadyMediaProbe : IMediaEngineProbe
    {
        public HubCapabilityStatus Detect(HubMediaSettings settings) => new("ready", "Ready");

        public string? ResolveExecutable(HubMediaSettings settings) => "/tmp/ffmpeg";
    }
}
