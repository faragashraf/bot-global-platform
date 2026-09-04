using BotGlobal.Pairing;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingPersistenceModelTests
{
    private static PairingDbContext CreateContext()
    {
        var options =
            new DbContextOptionsBuilder<PairingDbContext>()
                .UseSqlServer(
                    "Server=localhost;"
                    + "Database=PairingModelTests;"
                    + "User Id=test;"
                    + "Password=NotUsed;"
                    + "Encrypt=False;"
                    + "TrustServerCertificate=True")
                .Options;

        return new PairingDbContext(options);
    }

    [Fact]
    public void Model_uses_pairing_schema_only()
    {
        using var context = CreateContext();

        var schemas = context.Model
            .GetEntityTypes()
            .Select(entity => entity.GetSchema())
            .Where(schema => schema is not null)
            .Select(schema => schema!)
            .Distinct(StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(new[] { PairingModule.DatabaseSchema }, schemas);
    }

    [Fact]
    public void Token_hash_is_unique_and_fixed_binary()
    {
        using var context = CreateContext();

        var entity =
            context.Model.FindEntityType(typeof(PairingChallenge))!;

        var tokenHash =
            entity.FindProperty(nameof(PairingChallenge.TokenHash))!;

        Assert.Equal("binary(32)", tokenHash.GetColumnType());

        var index = entity
            .GetIndexes()
            .Single(index =>
                index.GetDatabaseName()
                == "UX_PairingChallenges_TokenHash");

        Assert.True(index.IsUnique);
    }

    [Fact]
    public void Platform_client_ownership_is_value_reference_not_cross_module_foreign_key()
    {
        using var context = CreateContext();

        var entity =
            context.Model.FindEntityType(typeof(PairingChallenge))!;

        Assert.NotNull(entity.FindProperty(nameof(PairingChallenge.PlatformClientId)));
        Assert.Empty(entity.GetForeignKeys());
    }

    [Fact]
    public void Concurrency_stamp_is_configured_for_atomic_claim_protection()
    {
        using var context = CreateContext();

        var property =
            context.Model
                .FindEntityType(typeof(PairingChallenge))!
                .FindProperty(nameof(PairingChallenge.ConcurrencyStamp))!;

        Assert.True(property.IsConcurrencyToken);
    }

    [Fact]
    public void Device_and_push_registration_model_preserves_application_isolation()
    {
        using var context = CreateContext();
        var device = context.Model.FindEntityType(typeof(MobileDevice))!;
        var registration = context.Model.FindEntityType(
            typeof(MobilePushRegistration))!;

        var applicationInstallation = device
            .GetIndexes()
            .Single(index =>
                index.Properties.Select(property => property.Name)
                    .SequenceEqual(
                    [
                        nameof(MobileDevice.PlatformClientId),
                        nameof(MobileDevice.InstallationId)
                    ]));

        Assert.True(applicationInstallation.IsUnique);

        var foreignKey = Assert.Single(
            registration.GetForeignKeys());
        Assert.Equal(typeof(MobileDevice), foreignKey.PrincipalEntityType.ClrType);
        Assert.Equal(
            nameof(MobilePushRegistration.MobileDeviceId),
            Assert.Single(foreignKey.Properties).Name);
    }

    [Fact]
    public void ProfileProjectionHasApplicationSubjectUniquenessAndConcurrency()
    {
        using var context = CreateContext();
        var entity = context.Model.FindEntityType(typeof(MobileProfileSnapshot))!;
        var identity = entity.GetIndexes().Single(index =>
            index.GetDatabaseName()
            == "UX_MobileProfileSnapshots_PlatformClientId_ExternalSubjectId");

        Assert.True(identity.IsUnique);
        Assert.Equal(
            [
                nameof(MobileProfileSnapshot.PlatformClientId),
                nameof(MobileProfileSnapshot.ExternalSubjectId)
            ],
            identity.Properties.Select(property => property.Name));
        Assert.True(
            entity.FindProperty(nameof(MobileProfileSnapshot.RowVersion))!
                .IsConcurrencyToken);
        Assert.Empty(entity.GetForeignKeys());
    }
}
