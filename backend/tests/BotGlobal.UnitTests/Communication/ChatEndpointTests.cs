using System.Net;
using BotGlobal.Communication.Application.Chat;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Pairing.Security;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatEndpointTests
{
    [Fact]
    public async Task EveryRouteMapsWithoutInferredServicesAndRequiresAuthentication()
    {
        await using var app = await Host(0);
        var client = app.GetTestClient();
        var id = Guid.NewGuid();
        foreach (var (method, path) in new[] {
            ("GET", "context"), ("GET", "conversations"), ("POST", "conversations/direct"),
            ("GET", $"conversations/{id}/messages"), ("POST", $"conversations/{id}/messages/text"),
            ("POST", $"conversations/{id}/messages/voice"), ("GET", $"voice/{id}"),
            ("POST", $"voice/{id}/durable-download"), ("PUT", $"conversations/{id}/read") })
        {
            using var response = await client.SendAsync(new HttpRequestMessage(new HttpMethod(method), $"/api/mobile/communications/chat/{path}"));
            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }
    }

    [Theory]
    [InlineData("session", 1, true, HttpStatusCode.OK)]
    [InlineData("device", 1, true, HttpStatusCode.OK)]
    [InlineData("session", 0, true, HttpStatusCode.Forbidden)]
    [InlineData("device", 2, true, HttpStatusCode.Forbidden)]
    [InlineData("session", 1, false, HttpStatusCode.Forbidden)]
    [InlineData("revoked", 1, true, HttpStatusCode.Unauthorized)]
    [InlineData("mixed", 1, true, HttpStatusCode.Unauthorized)]
    public async Task ContextUsesRealHandlersAndDefaultDenyRegistry(string credential, int adapterCount, bool participant, HttpStatusCode expected)
    {
        await using var app = await Host(adapterCount, participant);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("Authorization", "Bearer " + credential);
        using var response = await client.GetAsync("/api/mobile/communications/chat/context");
        Assert.Equal(expected, response.StatusCode);
        if (expected == HttpStatusCode.OK)
        {
            var body = await response.Content.ReadAsStringAsync();
            Assert.Contains("opaque:subject", body);
            Assert.Contains(Actors.ApplicationId.ToString(), body);
            Assert.DoesNotContain("Bearer", body);
        }
    }

    private static async Task<WebApplication> Host(int adapterCount, bool participant = true)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions {
            ContentRootPath = AppContext.BaseDirectory, EnvironmentName = "Testing",
            Args = ["--hostBuilder:reloadConfigOnChange=false"],
        });
        builder.WebHost.UseTestServer();
        builder.Logging.ClearProviders();
        builder.Services.AddAuthentication()
            .AddScheme<AuthenticationSchemeOptions, MobileApplicationAuthenticationHandler>(ApplicationIdentityDefaults.Scheme, _ => { })
            .AddScheme<AuthenticationSchemeOptions, MobileDeviceAuthenticationHandler>(MobileDeviceAuthenticationDefaults.Scheme, _ => { });
        builder.Services.AddAuthorization();
        var actors = new Actors();
        builder.Services.AddSingleton<IMobileApplicationSessionAuthenticator>(actors);
        builder.Services.AddSingleton<IMobileDeviceAuthenticator>(actors);
        builder.Services.AddSingleton<IPlatformClientApplicationResolver>(actors);
        builder.Services.AddSingleton<IPlatformClientDescriptorReader>(actors);
        builder.Services.AddSingleton<IChatActorResolver, ChatActorResolver>();
        builder.Services.AddSingleton<ChatPolicyRegistry>();
        for (var i = 0; i < adapterCount; i++)
        {
            builder.Services.AddSingleton<IChatParticipantDirectory>(new Adapter(participant));
            builder.Services.AddSingleton<IChatAccessPolicy>(new Adapter(participant));
        }
        // Intentionally no IChatEngine: route discovery must not infer it as request data.
        var app = builder.Build();
        app.UseAuthentication(); app.UseAuthorization(); app.MapChatEndpoints();
        await app.StartAsync();
        return app;
    }

    private sealed class Actors : IMobileApplicationSessionAuthenticator, IMobileDeviceAuthenticator,
        IPlatformClientApplicationResolver, IPlatformClientDescriptorReader
    {
        public static readonly Guid ApplicationId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        private static readonly PlatformClientDescriptor App = new(ApplicationId, "test-chat", "Synthetic", true);
        public Task<AuthenticatedApplicationSession?> AuthenticateAsync(string token, CancellationToken cancellationToken) =>
            Task.FromResult(token is "session" or "mixed" ? new AuthenticatedApplicationSession(Guid.NewGuid(),
                new ApplicationIdentityDescriptor(Guid.NewGuid(), null, "opaque:subject", App.ClientKey, "Synthetic", false)) : null);
        Task<AuthenticatedMobileDevice?> IMobileDeviceAuthenticator.AuthenticateAsync(string token, CancellationToken cancellationToken) =>
            Task.FromResult(token is "device" or "mixed" ? new AuthenticatedMobileDevice(Guid.NewGuid(), ApplicationId, "opaque:subject") : null);
        public Task<PlatformClientDescriptor?> FindByClientKeyAsync(string key, CancellationToken token) => Task.FromResult(key == App.ClientKey ? App : null);
        public Task<PlatformClientDescriptor?> FindAsync(Guid id, CancellationToken token) => Task.FromResult(id == ApplicationId ? App : null);
    }
    private sealed class Adapter(bool participant) : IChatParticipantDirectory, IChatAccessPolicy
    {
        public string ApplicationKey => "test-chat";
        public Task<ChatParticipant?> FindBySubjectAsync(ChatApplication application, string subjectId, CancellationToken token) =>
            Task.FromResult(participant && application.ApplicationId == Actors.ApplicationId && subjectId == "opaque:subject"
                ? new ChatParticipant(subjectId, "reference", "Synthetic") : null);
        public Task<ChatParticipant?> FindByReferenceAsync(ChatApplication application, string reference, CancellationToken token) => Task.FromResult<ChatParticipant?>(null);
        public Task<bool> CanStartDirectConversationAsync(ChatActor actor, ChatParticipant self, ChatParticipant other, CancellationToken token) => Task.FromResult(false);
        public Task<bool> IsBidirectionallyBlockedAsync(ChatApplication application, ChatParticipant first, ChatParticipant second, CancellationToken token) => Task.FromResult(true);
    }
}
