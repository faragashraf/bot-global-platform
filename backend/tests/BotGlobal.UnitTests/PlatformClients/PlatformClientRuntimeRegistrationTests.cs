using BotGlobal.PlatformClients;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using BotGlobal.PlatformClients.Application.Capabilities;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientRuntimeRegistrationTests
{
    [Fact]
    public void Dedicated_connection_string_is_required()
    {
        var services = new ServiceCollection();

        var configuration =
            new ConfigurationBuilder().Build();

        var exception = Assert.Throws<InvalidOperationException>(
            () => services.AddPlatformClientsModule(configuration));

        Assert.Contains(
            PlatformClientsModule.ConnectionStringName,
            exception.Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Dedicated_connection_registers_platform_clients_db_context()
    {
        var services = new ServiceCollection();

        services.AddPlatformClientsModule(
            BuildConfiguration());

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        var context = scope.ServiceProvider
            .GetRequiredService<PlatformClientsDbContext>();

        Assert.NotNull(context);

        var connectionString =
            context.Database.GetConnectionString();

        Assert.Contains(
            "PlatformClientsRegistrationTests",
            connectionString,
            StringComparison.Ordinal);
    }

    [Fact]
    public void Registration_does_not_need_identity_catalog_or_communication_connections()
    {
        var services = new ServiceCollection();

        services.AddPlatformClientsModule(
            BuildConfiguration());

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        Assert.NotNull(
            scope.ServiceProvider
                .GetRequiredService<PlatformClientsDbContext>());
    }

    [Fact]
    public void CapabilityCatalogIncludesProductNeutralProfilePublishing()
    {
        var services = new ServiceCollection();
        services.AddPlatformClientsModule(BuildConfiguration());

        using var provider = services.BuildServiceProvider();
        var catalog = provider.GetRequiredService<IPlatformCapabilityCatalog>();
        var capability = Assert.Single(
            catalog.GetAll(),
            item => item.Capability == "profiles:publish");

        Assert.Equal(PlatformCapabilityImpact.Medium, capability.Impact);
        Assert.DoesNotContain(
            "ENPO",
            capability.Description,
            StringComparison.OrdinalIgnoreCase);
    }

    private static IConfiguration BuildConfiguration()
    {
        return new ConfigurationBuilder()
            .AddInMemoryCollection(
                new Dictionary<string, string?>
                {
                    ["ConnectionStrings:PlatformClients"] =
                        "Server=localhost;"
                        + "Database=PlatformClientsRegistrationTests;"
                        + "User Id=test;"
                        + "Password=NotUsed;"
                        + "Encrypt=False;"
                        + "TrustServerCertificate=True"
                })
            .Build();
    }
}
