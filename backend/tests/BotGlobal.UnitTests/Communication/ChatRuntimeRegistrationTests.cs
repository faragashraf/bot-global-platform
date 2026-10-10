using BotGlobal.Communication;
using BotGlobal.Communication.Application.Chat;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatRuntimeRegistrationTests
{
    [Theory]
    [InlineData(false, null, false)]
    [InlineData(true, null, false)]
    [InlineData(false, "false", false)]
    [InlineData(true, "false", false)]
    [InlineData(false, "true", true)]
    [InlineData(true, "true", true)]
    public void Actual_composition_registers_only_explicit_chat_workers(
        bool canary, string? enabled, bool expected)
    {
        var services = Compose(canary, enabled);
        var workers = services.Where(x => x.ServiceType == typeof(IHostedService))
            .Select(x => x.ImplementationType).ToArray();
        Assert.Equal(expected, workers.Contains(typeof(ChatDispatchBackgroundService)));
        Assert.Equal(expected, workers.Contains(typeof(ChatMaintenanceBackgroundService)));
        Assert.Equal(expected ? 2 : 0, workers.Count(x => x == typeof(ChatDispatchBackgroundService)
            || x == typeof(ChatMaintenanceBackgroundService)));

        // Resolve only options, never hosted services, storage, DB or Firebase clients.
        using var provider = services.BuildServiceProvider();
        Assert.Equal(expected, provider.GetRequiredService<IOptions<ChatRuntimeOptions>>().Value.WorkersEnabled);
        Assert.Equal(7, provider.GetRequiredService<IOptions<ChatVoiceOptions>>().Value.PublishedRetentionDays);

        var inactiveWorkers = Compose(canary, "false")
            .Where(x => x.ServiceType == typeof(IHostedService)).Select(x => x.ImplementationType);
        Assert.Equal(inactiveWorkers, workers.Where(x => x != typeof(ChatDispatchBackgroundService)
            && x != typeof(ChatMaintenanceBackgroundService)));
    }

    [Theory]
    [InlineData("PublishedRetentionDays", "0")]
    [InlineData("PublishedRetentionDays", "8")]
    [InlineData("SweepMinutes", "0")]
    [InlineData("SweepMinutes", "61")]
    [InlineData("StoragePath", "relative/voice")]
    [InlineData("StoragePath", "")]
    [InlineData("DecoderPath", "")]
    public void Active_chat_rejects_invalid_voice_settings(string key, string value)
    {
        using var provider = Compose(true, "true", $"Communication:ChatVoice:{key}", value).BuildServiceProvider();
        Assert.Throws<OptionsValidationException>(() => provider.GetRequiredService<IOptions<ChatVoiceOptions>>().Value);
    }

    [Fact]
    public void Active_chat_requires_authoritative_provider()
    {
        using var provider = Compose(false, "true", "Database:Provider", "SqlServer").BuildServiceProvider();
        Assert.Throws<OptionsValidationException>(() => provider.GetRequiredService<IOptions<ChatRuntimeOptions>>().Value);
    }

    [Fact]
    public void Malformed_activation_is_not_silently_treated_as_disabled() =>
        Assert.Throws<InvalidOperationException>(() => Compose(true, "not-a-boolean"));

    private static IServiceCollection Compose(bool canary, string? enabled, string? key = null, string? value = null)
    {
        var values = new Dictionary<string, string?>
        {
            ["ConnectionStrings:Communication"] = "Host=127.0.0.1;Database=chat_rehearsal_model;Username=unused",
            ["Database:Provider"] = "PostgreSql",
            ["Database:CanarySchema:EnsureCreated"] = canary.ToString(),
            ["Communication:ChatVoice:StoragePath"] = Path.Combine(Path.GetTempPath(), "chat-registration-unused"),
            ["Firebase:Enabled"] = "false"
        };
        if (enabled is not null) values["Communication:ChatRuntime:WorkersEnabled"] = enabled;
        if (key is not null) values[key] = value;
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddCommunicationModule(new ConfigurationBuilder().AddInMemoryCollection(values).Build());
        return services;
    }
}
