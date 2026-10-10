using BotGlobal.Calling;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Realtime;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallingRuntimeRegistrationTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Call_lifecycle_workers_run_even_when_canary_schema_uses_ensure_created(bool canaryEnsureCreated)
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddCallingModule(new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ConnectionStrings:Communication"] = "Host=127.0.0.1;Database=calling_runtime_model;Username=unused",
            ["Database:Provider"] = "PostgreSql",
            ["Database:CanarySchema:EnsureCreated"] = canaryEnsureCreated.ToString()
        }).Build());

        var workers = services.Where(descriptor => descriptor.ServiceType == typeof(IHostedService))
            .Select(descriptor => descriptor.ImplementationType)
            .ToArray();

        Assert.Contains(typeof(CallActivityRecoveryHostedService), workers);
        Assert.Contains(typeof(CallExpiryBackgroundService), workers);
    }
}
