using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Identity.Application;

internal sealed class ApplicationAccountDeletionBackgroundService(
    IServiceScopeFactory scopeFactory,
    ILogger<ApplicationAccountDeletionBackgroundService> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await ProcessAsync(stoppingToken);
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
        {
            await ProcessAsync(stoppingToken);
        }
    }

    private async Task ProcessAsync(CancellationToken cancellationToken)
    {
        try
        {
            await using var scope = scopeFactory.CreateAsyncScope();
            await scope.ServiceProvider
                .GetRequiredService<ApplicationAccountDeletionProcessor>()
                .ProcessPendingAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            logger.LogWarning(
                "NQRB account deletion recovery pass failed. ErrorType={ErrorType}",
                error.GetType().Name);
        }
    }
}
