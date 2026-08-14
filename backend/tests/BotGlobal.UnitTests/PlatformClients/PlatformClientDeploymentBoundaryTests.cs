using BotGlobal.PlatformClients;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientDeploymentBoundaryTests
{
    [Fact]
    public void Module_owns_independent_configuration_names()
    {
        Assert.Equal("PlatformClients", PlatformClientsModule.ConnectionStringName);
        Assert.Equal("platform_clients", PlatformClientsModule.DatabaseSchema);
        Assert.Equal("__EFMigrationsHistory", PlatformClientsModule.MigrationsHistoryTableName);
    }

    [Fact]
    public void Model_uses_module_owned_schema()
    {
        var options =
            new DbContextOptionsBuilder<PlatformClientsDbContext>()
                .UseSqlServer(
                    "Server=localhost;"
                    + "Database=ModelOnly;"
                    + "User Id=test;"
                    + "Password=NotUsed;"
                    + "Encrypt=False;"
                    + "TrustServerCertificate=True")
                .Options;

        using var context = new PlatformClientsDbContext(options);

        Assert.All(
            context.Model.GetEntityTypes(),
            entity => Assert.Equal(
                PlatformClientsModule.DatabaseSchema,
                entity.GetSchema()));
    }
}
