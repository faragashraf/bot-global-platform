using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using SentriCam.Application.Hub;
using SentriCam.Contracts.Hub;

namespace SentriCam.Infrastructure.Hub;

public sealed class HubStartupProvisioningService(
    IHubSetupStore store,
    IServiceScopeFactory scopeFactory,
    ILogger<HubStartupProvisioningService> logger) : IHostedService
{
    private static readonly Action<ILogger, Exception?> LogHealthCheckFailure =
        LoggerMessage.Define(
            LogLevel.Error,
            new EventId(6100, nameof(LogHealthCheckFailure)),
            "The configured Local Hub failed its startup health check.");

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (!store.Current.State.Equals(HubSetupStates.Completed, StringComparison.Ordinal)
            || !store.Current.IsConfigured)
        {
            return;
        }
        try
        {
            await using var scope = scopeFactory.CreateAsyncScope();
            var statusProbe = scope.ServiceProvider.GetRequiredService<IHubStatusProbe>();
            var status = await statusProbe.ReadAsync(cancellationToken);
            if (!status.IsReady)
            {
                await store.MarkNeedsRepairAsync(
                    "startup_health_failed",
                    "The Hub needs attention before the dashboard can open. Check storage and database access, then retry setup.",
                    cancellationToken);
            }
        }
        catch (Exception exception)
        {
            LogHealthCheckFailure(logger, exception);
            try
            {
                await store.MarkNeedsRepairAsync(
                    "startup_health_failed",
                    "The Hub needs attention before the dashboard can open. Check storage and database access, then retry setup.",
                    cancellationToken);
            }
            catch (Exception persistenceException)
            {
                LogHealthCheckFailure(logger, persistenceException);
            }
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
