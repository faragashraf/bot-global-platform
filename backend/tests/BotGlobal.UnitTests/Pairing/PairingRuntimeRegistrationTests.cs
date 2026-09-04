using BotGlobal.Pairing;
using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using BotGlobal.Pairing.Application.MobileDevices;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Application.Profiles;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingRuntimeRegistrationTests
{
    [Fact]
    public void Dedicated_connection_string_is_required()
    {
        var services = new ServiceCollection();
        var configuration = new ConfigurationBuilder().Build();

        var exception = Assert.Throws<InvalidOperationException>(
            () => services.AddPairingModule(configuration));

        Assert.Contains(
            PairingModule.ConnectionStringName,
            exception.Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Dedicated_connection_registers_pairing_services_and_context()
    {
        var services = new ServiceCollection();

        services.AddSingleton<IPlatformClientApplicationResolver>(
            new EmptyApplicationResolver());

        services.AddPairingModule(BuildConfiguration());

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        Assert.NotNull(
            scope.ServiceProvider.GetRequiredService<PairingDbContext>());
        Assert.NotNull(
            scope.ServiceProvider.GetRequiredService<IPairingTokenService>());
        Assert.NotNull(
            scope.ServiceProvider.GetRequiredService<IPairingChallengeService>());
        Assert.NotNull(
            scope.ServiceProvider.GetRequiredService<IMobileDeviceEnrollmentService>());
        Assert.NotNull(
            scope.ServiceProvider.GetRequiredService<IMobileProfileSnapshotService>());

        var connectionString =
            scope.ServiceProvider
                .GetRequiredService<PairingDbContext>()
                .Database
                .GetConnectionString();

        Assert.Contains(
            "PairingRegistrationTests",
            connectionString,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Pairing_module_owns_dedicated_schema_and_migration_history()
    {
        Assert.Equal("Pairing", PairingModule.ConnectionStringName);
        Assert.Equal("pairing", PairingModule.DatabaseSchema);
        Assert.Equal("__EFMigrationsHistory", PairingModule.MigrationsHistoryTableName);
    }

    private static IConfiguration BuildConfiguration()
    {
        return new ConfigurationBuilder()
            .AddInMemoryCollection(
                new Dictionary<string, string?>
                {
                    ["ConnectionStrings:Pairing"] =
                        "Server=localhost;"
                        + "Database=PairingRegistrationTests;"
                        + "User Id=test;"
                        + "Password=NotUsed;"
                        + "Encrypt=False;"
                        + "TrustServerCertificate=True"
                })
            .Build();
    }

    private sealed class EmptyApplicationResolver
        : IPlatformClientApplicationResolver
    {
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(
            string clientKey,
            CancellationToken cancellationToken) =>
            Task.FromResult<PlatformClientDescriptor?>(null);
    }
}
