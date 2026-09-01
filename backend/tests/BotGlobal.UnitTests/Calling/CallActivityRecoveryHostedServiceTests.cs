using BotGlobal.Calling.Application;
using BotGlobal.Calling.Domain;
using BotGlobal.Calling.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallActivityRecoveryHostedServiceTests
{
    [Fact]
    public async Task Startup_marks_only_non_terminal_records_failed_after_in_memory_registry_loss()
    {
        var options = new DbContextOptionsBuilder<CallingDbContext>()
            .UseInMemoryDatabase($"calling-recovery-{Guid.NewGuid():N}").Options;
        var ringing = new CallRecord(Guid.NewGuid(), Guid.NewGuid(), "nqrb", DateTimeOffset.UtcNow);
        var completed = new CallRecord(Guid.NewGuid(), ringing.ApplicationId, "nqrb", DateTimeOffset.UtcNow);
        completed.Answer(DateTimeOffset.UtcNow);
        completed.Finish(DurableCallOutcome.Completed, "ended", DateTimeOffset.UtcNow);
        await using (var seed = new CallingDbContext(options))
        {
            seed.Calls.AddRange(ringing, completed);
            await seed.SaveChangesAsync();
        }
        var services = new ServiceCollection().AddScoped(_ => new CallingDbContext(options)).BuildServiceProvider();
        await using (services)
        {
            var worker = new CallActivityRecoveryHostedService(services.GetRequiredService<IServiceScopeFactory>(),
                TimeProvider.System, NullLogger<CallActivityRecoveryHostedService>.Instance);
            await worker.StartAsync(default);
        }
        await using var verify = new CallingDbContext(options);

        var recovered = await verify.Calls.SingleAsync(call => call.Id == ringing.Id);
        var unchanged = await verify.Calls.SingleAsync(call => call.Id == completed.Id);
        Assert.Equal(DurableCallState.Terminal, recovered.State);
        Assert.Equal(DurableCallOutcome.Failed, recovered.Outcome);
        Assert.Equal("backend_restarted", recovered.EndReason);
        Assert.Equal(DurableCallOutcome.Completed, unchanged.Outcome);
    }
}
