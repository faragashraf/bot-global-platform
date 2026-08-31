using BotGlobal.Communication;
using BotGlobal.Communication.Application.MobileNotifications.Fcm;
using BotGlobal.Communication.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

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

    [Fact]
    public void Legacy_firebase_configuration_registers_the_admin_sender()
    {
        var applicationId = Guid.NewGuid();
        var services = new ServiceCollection();
        var configuration = BuildConfiguration(
            new Dictionary<string, string?>
            {
                ["Firebase:Enabled"] = "true",
                ["Firebase:ApplicationId"] = applicationId.ToString(),
                ["Firebase:ConfigurationReference"] = "legacy-firebase",
                ["Firebase:ProjectId"] = "legacy-project",
                ["Firebase:CredentialPath"] = "/test/legacy-credential.json",
                ["Notifications:PushProviders:Providers:0:ApplicationId"] = applicationId.ToString(),
                ["Notifications:PushProviders:Providers:0:Provider"] = "fcm",
                ["Notifications:PushProviders:Providers:0:Enabled"] = "true",
                ["Notifications:PushProviders:Providers:0:ConfigurationReference"] = "legacy-firebase",
                ["Notifications:PushProviders:Providers:0:FirebaseProjectId"] = "legacy-project",
                ["Notifications:PushProviders:Providers:0:AndroidPackageName"] = "com.botglobal.legacy"
            });

        services.AddCommunicationModule(configuration);

        Assert.Contains(
            services,
            descriptor => descriptor.ServiceType == typeof(IFcmPushSender)
                && descriptor.ImplementationType == typeof(FirebaseAdminFcmPushSender));
        Assert.Contains(
            services,
            descriptor => descriptor.ServiceType == typeof(IFirebaseMessagingResolver));
        Assert.Contains(
            services,
            descriptor => descriptor.ServiceType == typeof(IHostedService)
                && descriptor.ImplementationType
                    == typeof(FirebaseMessagingInitializationService));
    }

    [Fact]
    public void Two_application_scoped_firebase_profiles_register_together()
    {
        var appA = Guid.NewGuid();
        var appB = Guid.NewGuid();
        var services = new ServiceCollection();
        var values = new Dictionary<string, string?>();

        AddProfile(values, 0, appA, "firebase-a", "project-a", "com.botglobal.a");
        AddProfile(values, 1, appB, "firebase-b", "project-b", "com.botglobal.b");

        services.AddCommunicationModule(BuildConfiguration(values));

        Assert.Contains(
            services,
            descriptor => descriptor.ServiceType == typeof(IFcmPushSender)
                && descriptor.ImplementationType == typeof(FirebaseAdminFcmPushSender));
    }

    [Fact]
    public void Firebase_profile_and_provider_project_mismatch_fails_startup_registration()
    {
        var applicationId = Guid.NewGuid();
        var services = new ServiceCollection();
        var values = new Dictionary<string, string?>();
        AddProfile(
            values,
            0,
            applicationId,
            "nqrb-firebase",
            "nqrb-project",
            "com.botglobal.nqrb");
        values["Notifications:PushProviders:Providers:0:FirebaseProjectId"] =
            "different-project";

        var exception = Assert.Throws<InvalidOperationException>(
            () => services.AddCommunicationModule(BuildConfiguration(values)));

        Assert.Equal(
            "Firebase runtime profiles must match their application-scoped FCM provider entries.",
            exception.Message);
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

    private static IConfiguration BuildConfiguration(
        IReadOnlyDictionary<string, string?> values)
    {
        var configuration = new Dictionary<string, string?>(values)
        {
            [$"ConnectionStrings:{CommunicationModule.ConnectionStringName}"] =
                "Server=localhost;Database=CommunicationRegistrationTests;User Id=test;Password=NotUsed;Encrypt=False;TrustServerCertificate=True"
        };

        return new ConfigurationBuilder()
            .AddInMemoryCollection(configuration)
            .Build();
    }

    private static void AddProfile(
        IDictionary<string, string?> values,
        int index,
        Guid applicationId,
        string configurationReference,
        string projectId,
        string packageName)
    {
        values[$"Firebase:Profiles:{index}:Enabled"] = "true";
        values[$"Firebase:Profiles:{index}:ApplicationId"] = applicationId.ToString();
        values[$"Firebase:Profiles:{index}:ConfigurationReference"] = configurationReference;
        values[$"Firebase:Profiles:{index}:ProjectId"] = projectId;
        values[$"Firebase:Profiles:{index}:CredentialPath"] = $"/test/{configurationReference}.json";
        values[$"Notifications:PushProviders:Providers:{index}:ApplicationId"] = applicationId.ToString();
        values[$"Notifications:PushProviders:Providers:{index}:Provider"] = "fcm";
        values[$"Notifications:PushProviders:Providers:{index}:Enabled"] = "true";
        values[$"Notifications:PushProviders:Providers:{index}:ConfigurationReference"] = configurationReference;
        values[$"Notifications:PushProviders:Providers:{index}:FirebaseProjectId"] = projectId;
        values[$"Notifications:PushProviders:Providers:{index}:AndroidPackageName"] = packageName;
    }
}
