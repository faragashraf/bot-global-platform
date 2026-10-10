using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Infrastructure;
using BotGlobal.Pairing.Security;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class ChatAuthenticationTests
{
    [Fact]
    public async Task QueryCredentialsAreRestrictedToExactChatAndExistingLegacyHubs()
    {
        foreach (var path in new[] { "/hubs/chat", "/hubs/chat/negotiate", "/hubs/chatty", "/hubs/chat/other", "/api/mobile/communications/chat/context", "/hubs/games", MobileNotificationRealtimeContract.HubPath })
        {
            var context = new DefaultHttpContext(); context.Request.Path = path; context.Request.QueryString = new("?access_token=synthetic");
            var session = new MobileApplicationAuthenticationHandler(new Monitor(), NullLoggerFactory.Instance, UrlEncoder.Default, new Sessions());
            await session.InitializeAsync(new AuthenticationScheme(ApplicationIdentityDefaults.Scheme, null, typeof(MobileApplicationAuthenticationHandler)), context);
            Assert.Equal(path is "/hubs/chat" or "/hubs/chat/negotiate" or "/hubs/games", (await session.AuthenticateAsync()).Succeeded);
            var device = new MobileDeviceAuthenticationHandler(new Monitor(), NullLoggerFactory.Instance, UrlEncoder.Default, new Devices());
            await device.InitializeAsync(new AuthenticationScheme(MobileDeviceAuthenticationDefaults.Scheme, null, typeof(MobileDeviceAuthenticationHandler)), context);
            Assert.Equal(path is "/hubs/chat" or "/hubs/chat/negotiate" || path == MobileNotificationRealtimeContract.HubPath, (await device.AuthenticateAsync()).Succeeded);
        }
    }

    [Fact]
    public async Task ExistingSocketCredentialRevalidationRejectsRevocationAndReplacement()
    {
        var sessions = new Sessions(); var devices = new Devices();
        using var services = new ServiceCollection().AddSingleton<IMobileApplicationSessionAuthenticator>(sessions).AddSingleton<IMobileDeviceAuthenticator>(devices).BuildServiceProvider();
        var context = new DefaultHttpContext(); context.Request.Path = "/hubs/chat"; context.Request.Headers.Authorization = "Bearer synthetic";
        var session = new MobileApplicationAuthenticationHandler(new Monitor(), NullLoggerFactory.Instance, UrlEncoder.Default, sessions);
        await session.InitializeAsync(new AuthenticationScheme(ApplicationIdentityDefaults.Scheme, null, typeof(MobileApplicationAuthenticationHandler)), context);
        Assert.True((await session.AuthenticateAsync()).Succeeded);
        var sessionLease = Assert.IsType<ChatConnectionCredential>(context.Items[ChatConnectionCredential.Key(ChatActorMechanism.ApplicationSession)]);
        Assert.True(await sessionLease.ValidateAsync(services, default)); sessions.Revoked = true;
        Assert.False(await sessionLease.ValidateAsync(services, default));

        var device = new MobileDeviceAuthenticationHandler(new Monitor(), NullLoggerFactory.Instance, UrlEncoder.Default, devices);
        await device.InitializeAsync(new AuthenticationScheme(MobileDeviceAuthenticationDefaults.Scheme, null, typeof(MobileDeviceAuthenticationHandler)), context);
        Assert.True((await device.AuthenticateAsync()).Succeeded);
        var deviceLease = Assert.IsType<ChatConnectionCredential>(context.Items[ChatConnectionCredential.Key(ChatActorMechanism.PairedDevice)]);
        Assert.True(await deviceLease.ValidateAsync(services, default)); devices.Replace();
        Assert.False(await deviceLease.ValidateAsync(services, default));
    }

    private sealed class Monitor : IOptionsMonitor<AuthenticationSchemeOptions>
    {
        public AuthenticationSchemeOptions CurrentValue { get; } = new();
        public AuthenticationSchemeOptions Get(string? name) => CurrentValue;
        public IDisposable? OnChange(Action<AuthenticationSchemeOptions, string?> listener) => null;
    }
    private sealed class Sessions : IMobileApplicationSessionAuthenticator
    {
        public bool Revoked { get; set; }
        private readonly AuthenticatedApplicationSession _session = new(Guid.NewGuid(), new ApplicationIdentityDescriptor(Guid.NewGuid(), null, "opaque:subject", "app", "Synthetic", false));
        public Task<AuthenticatedApplicationSession?> AuthenticateAsync(string token, CancellationToken cancellationToken) => Task.FromResult(Revoked ? null : _session);
    }
    private sealed class Devices : IMobileDeviceAuthenticator
    {
        private AuthenticatedMobileDevice _device = new(Guid.NewGuid(), Guid.NewGuid(), "opaque:subject");
        public void Replace() => _device = _device with { DeviceId = Guid.NewGuid() };
        public Task<AuthenticatedMobileDevice?> AuthenticateAsync(string token, CancellationToken cancellationToken) => Task.FromResult<AuthenticatedMobileDevice?>(_device);
    }
}
