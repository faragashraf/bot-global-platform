using BotGlobal.PlatformClients.Domain;
using BotGlobal.PlatformClients.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.PlatformClients;

public sealed class PlatformClientPersistenceModelTests
{
    private static PlatformClientsDbContext CreateContext()
    {
        var options =
            new DbContextOptionsBuilder<PlatformClientsDbContext>()
                .UseSqlServer(
                    "Server=localhost;"
                    + "Database=PlatformClientsModelTests;"
                    + "User Id=test;"
                    + "Password=NotUsed;"
                    + "Encrypt=False;"
                    + "TrustServerCertificate=True")
                .Options;

        return new PlatformClientsDbContext(options);
    }

    [Fact]
    public void Model_uses_platform_clients_schema_only()
    {
        using var context = CreateContext();

        var schemas = context.Model
            .GetEntityTypes()
            .Select(entity => entity.GetSchema())
            .Where(schema => schema is not null)
            .Select(schema => schema!)
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(new[] { "platform_clients" }, schemas);
    }

    [Fact]
    public void Client_key_is_unique()
    {
        using var context = CreateContext();

        var index = context.Model
            .FindEntityType(typeof(PlatformClient))!
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_PlatformClients_ClientKey");

        Assert.True(index.IsUnique);
    }

    [Fact]
    public void Secret_hash_is_fixed_32_byte_binary()
    {
        using var context = CreateContext();

        var property = context.Model
            .FindEntityType(typeof(PlatformClientCredential))!
            .FindProperty(nameof(PlatformClientCredential.SecretHash))!;

        Assert.Equal("binary(32)", property.GetColumnType());
    }

    [Fact]
    public void Capability_key_is_client_and_capability()
    {
        using var context = CreateContext();

        var entity = context.Model
            .FindEntityType(typeof(PlatformClientCapability))!;

        Assert.Equal(
            new[] { "ClientId", "Capability" },
            entity.FindPrimaryKey()!
                .Properties
                .Select(property => property.Name)
                .ToArray());
    }

    [Fact]
    public void Model_has_no_cross_module_foreign_keys()
    {
        using var context = CreateContext();

        var foreignKeys = context.Model
            .GetEntityTypes()
            .SelectMany(entity => entity.GetForeignKeys());

        Assert.All(
            foreignKeys,
            foreignKey =>
                Assert.StartsWith(
                    "BotGlobal.PlatformClients.",
                    foreignKey.PrincipalEntityType.ClrType.Namespace));
    }
}
