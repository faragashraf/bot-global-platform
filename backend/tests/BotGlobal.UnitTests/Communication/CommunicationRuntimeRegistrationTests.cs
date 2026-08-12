using BotGlobal.Communication;
using BotGlobal.Communication.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Communication;

public sealed class CommunicationRuntimeRegistrationTests
{
    [Fact]
    public void AddCommunicationModule_RequiresDedicatedConnectionString()
    {
        var services = new ServiceCollection();

        var configuration =
            new ConfigurationBuilder()
                .AddInMemoryCollection(
                    new Dictionary<string, string?>())
                .Build();

        var exception = Assert.Throws<InvalidOperationException>(
            () => services.AddCommunicationModule(configuration));

        Assert.Contains(
            CommunicationModule.ConnectionStringName,
            exception.Message,
            StringComparison.Ordinal);
    }

    [Fact]
    public void AddCommunicationModule_RegistersCommunicationDbContext()
    {
        var services = new ServiceCollection();

        var configuration =
            BuildConfiguration(
                "Server=localhost;"
                + "Database=CommunicationRegistrationTests;"
                + "User Id=test;"
                + "Password=NotUsed;"
                + "Encrypt=False;"
                + "TrustServerCertificate=True");

        services.AddCommunicationModule(configuration);

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        var context = scope.ServiceProvider
            .GetRequiredService<CommunicationDbContext>();

        Assert.NotNull(context);
    }

    [Fact]
    public void RuntimeSqlServerOptions_UseCommunicationMigrationHistory()
    {
        var services = new ServiceCollection();

        var configuration =
            BuildConfiguration(
                "Server=localhost;"
                + "Database=CommunicationRegistrationTests;"
                + "User Id=test;"
                + "Password=NotUsed;"
                + "Encrypt=False;"
                + "TrustServerCertificate=True");

        services.AddCommunicationModule(configuration);

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        var context = scope.ServiceProvider
            .GetRequiredService<CommunicationDbContext>();

        var options = context
            .GetService<IDbContextOptions>();

        var sqlServerExtension = options.Extensions
            .Single(extension =>
                extension.GetType().Name.Contains(
                    "SqlServerOptionsExtension",
                    StringComparison.Ordinal));

        var migrationHistorySchema =
            sqlServerExtension
                .GetType()
                .GetProperty("MigrationsHistoryTableSchema")
                ?.GetValue(sqlServerExtension)
                ?.ToString();

        var migrationHistoryTable =
            sqlServerExtension
                .GetType()
                .GetProperty("MigrationsHistoryTableName")
                ?.GetValue(sqlServerExtension)
                ?.ToString();

        Assert.Equal(
            CommunicationModule.DatabaseSchema,
            migrationHistorySchema);

        Assert.Equal(
            CommunicationModule.MigrationsHistoryTableName,
            migrationHistoryTable);
    }

    [Fact]
    public void DedicatedCommunicationConnectionDoesNotRequireIdentityOrCatalogConnection()
    {
        var services = new ServiceCollection();

        var configuration =
            BuildConfiguration(
                "Server=localhost;"
                + "Database=CommunicationOnly;"
                + "User Id=test;"
                + "Password=NotUsed;"
                + "Encrypt=False;"
                + "TrustServerCertificate=True");

        services.AddCommunicationModule(configuration);

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();

        Assert.NotNull(
            scope.ServiceProvider
                .GetRequiredService<CommunicationDbContext>());
    }

    private static IConfiguration BuildConfiguration(
        string communicationConnectionString)
    {
        return new ConfigurationBuilder()
            .AddInMemoryCollection(
                new Dictionary<string, string?>
                {
                    [
                        $"ConnectionStrings:{CommunicationModule.ConnectionStringName}"
                    ] = communicationConnectionString
                })
            .Build();
    }
}
