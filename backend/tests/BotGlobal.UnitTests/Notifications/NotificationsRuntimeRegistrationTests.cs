using BotGlobal.Notifications;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Notifications.Infrastructure.Persistence;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

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
}
