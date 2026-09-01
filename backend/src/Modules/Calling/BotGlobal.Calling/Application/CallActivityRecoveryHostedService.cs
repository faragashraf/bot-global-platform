using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Calling.Application;

internal sealed class CallActivityRecoveryHostedService(
    IServiceScopeFactory scopes,
    TimeProvider clock,
    ILogger<CallActivityRecoveryHostedService> logger) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopes.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<CallingDbContext>();
        var interrupted = await db.Calls
            .Where(call => call.State != DurableCallState.Terminal)
            .ToListAsync(cancellationToken);
        if (interrupted.Count == 0) return;
        var recoveredAt = clock.GetUtcNow();
        foreach (var call in interrupted)
            call.Finish(DurableCallOutcome.Failed, "backend_restarted", recoveredAt);
        await db.SaveChangesAsync(cancellationToken);
        logger.LogWarning("Recovered {InterruptedCallCount} interrupted durable call records after process startup.", interrupted.Count);
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
