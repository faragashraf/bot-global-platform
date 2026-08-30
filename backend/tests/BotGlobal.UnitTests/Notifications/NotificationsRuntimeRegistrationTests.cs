using BotGlobal.Notifications;
using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationsRuntimeRegistrationTests
{
    [Fact]
    public void Dedicated_connection_string_is_required()
    {
        var services = new ServiceCollection();
        var configuration = new ConfigurationBuilder().Build();

        var exception = Assert.Throws<InvalidOperationException>(
            () => services.AddNotificationsModule(configuration));

        Assert.Contains(NotificationsModule.ConnectionStringName, exception.Message);
    }

    [Fact]
    public void Registration_uses_only_notifications_connection()
    {
        var services = new ServiceCollection();
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["ConnectionStrings:Notifications"] =
                    "Server=localhost;Database=NotificationsRegistrationTests;Trusted_Connection=True;TrustServerCertificate=True"
            })
            .Build();

        services.AddLogging();
        services.AddNotificationsModule(configuration);

        using var provider = services.BuildServiceProvider();
        using var scope = provider.CreateScope();
        Assert.NotNull(scope.ServiceProvider.GetRequiredService<NotificationsDbContext>());
        Assert.NotNull(scope.ServiceProvider
            .GetRequiredService<NotificationDeliveryRecoveryProcessor>());
    }

    [Fact]
    public void Omitted_worker_enabled_uses_enabled_default_and_registers_worker()
    {
        var services = new ServiceCollection();
        services.AddNotificationsModule(Configuration());

        using var provider = services.BuildServiceProvider();

        Assert.True(provider.GetRequiredService<IOptions<NotificationCampaignOptions>>()
            .Value.Worker.Enabled);
        AssertWorkerRegistration(services, expected: true);
    }

    [Fact]
    public void Explicitly_enabled_worker_is_registered()
    {
        var services = new ServiceCollection();
        services.AddNotificationsModule(Configuration(
            ("Notifications:Worker:Enabled", "true")));

        AssertWorkerRegistration(services, expected: true);
    }

    [Fact]
    public void Disabled_worker_is_not_registered()
    {
        var services = new ServiceCollection();
        services.AddNotificationsModule(Configuration(
            ("Notifications:Worker:Enabled", "false")));

        AssertWorkerRegistration(services, expected: false);
    }

    [Fact]
    public void Disabled_worker_does_not_bypass_existing_options_validation()
    {
        var services = new ServiceCollection();
        services.AddNotificationsModule(Configuration(
            ("Notifications:Worker:Enabled", "false"),
            ("Notifications:Worker:BatchSize", "0")));

        using var provider = services.BuildServiceProvider();

        Assert.Throws<OptionsValidationException>(() =>
            provider.GetRequiredService<IOptions<NotificationCampaignOptions>>().Value);
    }

    private static IConfiguration Configuration(
        params (string Key, string Value)[] settings)
    {
        var values = new Dictionary<string, string?>
        {
            ["ConnectionStrings:Notifications"] =
                "Server=localhost;Database=NotificationsRegistrationTests;Trusted_Connection=True;TrustServerCertificate=True"
        };
        foreach (var (key, value) in settings)
        {
            values[key] = value;
        }

        return new ConfigurationBuilder()
            .AddInMemoryCollection(values)
            .Build();
    }

    private static void AssertWorkerRegistration(
        IServiceCollection services,
        bool expected)
    {
        var registered = services.Any(descriptor =>
            descriptor.ServiceType == typeof(IHostedService) &&
            descriptor.ImplementationType == typeof(NotificationCampaignBackgroundService));

        Assert.Equal(expected, registered);
    }
}
