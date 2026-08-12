using BotGlobal.Communication;
using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Contracts.Calls;
using BotGlobal.Communication.Hubs;
using Microsoft.AspNetCore.Authorization;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace BotGlobal.UnitTests.Communication;

public sealed class CommunicationFoundationTests
{
    [Fact]
    public void CommunicationHub_RequiresAuthorization()
    {
        var attribute = typeof(CommunicationHub)
            .GetCustomAttributes(
                typeof(AuthorizeAttribute),
                inherit: true)
            .SingleOrDefault();

        Assert.NotNull(attribute);
    }

    [Fact]
    public void CommunicationHub_DoesNotAcceptTrustedSenderIdentity()
    {
        var forbiddenParameter = typeof(CommunicationHub)
            .GetMethods()
            .SelectMany(method => method.GetParameters())
            .FirstOrDefault(parameter =>
                parameter.Name is not null
                && parameter.Name.Contains(
                    "senderUserId",
                    StringComparison.OrdinalIgnoreCase));

        Assert.Null(forbiddenParameter);
    }

    [Fact]
    public async Task FoundationAuthorizer_DeniesConversationAccess()
    {
        using var provider = BuildProvider();
        await using var scope = provider.CreateAsyncScope();

        var authorizer = scope.ServiceProvider
            .GetRequiredService<ICommunicationAuthorizer>();

        var allowed = await authorizer.CanAccessConversationAsync(
            "user-1",
            "conversation-1");

        Assert.False(allowed);
    }

    [Fact]
    public async Task FoundationAuthorizer_DeniesDirectContact()
    {
        using var provider = BuildProvider();
        await using var scope = provider.CreateAsyncScope();

        var authorizer = scope.ServiceProvider
            .GetRequiredService<ICommunicationAuthorizer>();

        var allowed = await authorizer.CanContactUserAsync(
            "user-1",
            "user-2");

        Assert.False(allowed);
    }

    [Fact]
    public async Task FoundationCallPreferences_DefaultToDisabled()
    {
        using var provider = BuildProvider();
        await using var scope = provider.CreateAsyncScope();

        var reader = scope.ServiceProvider
            .GetRequiredService<ICommunicationPreferencesReader>();

        CommunicationPreferences preferences =
            await reader.GetAsync("user-1");

        Assert.False(preferences.AllowVoiceCalls);
        Assert.False(preferences.AllowVideoCalls);
    }

    private static ServiceProvider BuildProvider()
    {
        var services = new ServiceCollection();

        var configuration =
            new ConfigurationBuilder()
                .AddInMemoryCollection(
                    new Dictionary<string, string?>
                    {
                        ["ConnectionStrings:Communication"] =
                            "Server=localhost;"
                            + "Database=CommunicationFoundationTests;"
                            + "User Id=test;"
                            + "Password=NotUsed;"
                            + "Encrypt=False;"
                            + "TrustServerCertificate=True"
                    })
                .Build();

        services.AddCommunicationModule(configuration);

        return services.BuildServiceProvider();
    }
}
