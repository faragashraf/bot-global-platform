using System.Net;
using System.Net.Http.Json;
using System.Security.Claims;
using BotGlobal.Api.Security;
using BotGlobal.Communication;
using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Application.MobileNotifications;
using BotGlobal.Communication.Contracts.MobileNotifications;
using BotGlobal.Communication.Endpoints;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Identity;
using BotGlobal.Identity.Application;
using BotGlobal.Notifications;
using BotGlobal.Notifications.Application.Processing;
using BotGlobal.Pairing.Security;
using BotGlobal.PlatformClients;
using BotGlobal.PlatformClients.Application.Authentication;
using BotGlobal.PlatformClients.Application.Credentials;
using BotGlobal.PlatformClients.Authentication;
using BotGlobal.PlatformClients.Authorization;
using BotGlobal.PlatformClients.Endpoints;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.SignalR;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace BotGlobal.UnitTests.Security;

public sealed class PlatformHttpSecurityTests
{
    private const string RotatePath = "/api/admin/platform-clients/11111111-1111-1111-1111-111111111111/credentials/rotate";
    private const string DiagnosticPath = "/api/communication/test/send-to-user";

    [Theory]
    [InlineData(false, HttpStatusCode.NotFound, 1)]
    [InlineData(true, HttpStatusCode.BadRequest, 0)]
    public async Task Cross_site_form_post_reproduces_the_original_cookie_mutation_and_is_blocked_by_the_convention(
        bool includeProtection, HttpStatusCode expected, int expectedCalls)
    {
        await using var app = await CreateAppAsync(includeProtection: includeProtection);
        var client = Client(app);
        await SignInAsync(client);
        using var request = new HttpRequestMessage(HttpMethod.Post, RotatePath);
        request.Headers.Add("Origin", "https://untrusted.example.test");
        request.Content = new FormUrlEncodedContent([]);

        var response = await client.SendAsync(request);

        Assert.Equal(expected, response.StatusCode);
        Assert.Equal(expectedCalls, app.Services.GetRequiredService<RecordingCredentialLifecycle>().Calls);
    }

    [Theory]
    [InlineData("POST")]
    [InlineData("PUT")]
    [InlineData("PATCH")]
    [InlineData("DELETE")]
    public async Task Cookie_mutations_require_valid_framework_proof(string method)
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);

        Assert.Equal(HttpStatusCode.BadRequest,
            (await client.SendAsync(new HttpRequestMessage(new HttpMethod(method), "/test/browser"))).StatusCode);

        var token = await BootstrapAsync(client);
        using var valid = new HttpRequestMessage(new HttpMethod(method), "/test/browser");
        valid.Headers.Add(PlatformHttpSecurity.HeaderName, token);
        Assert.Equal(HttpStatusCode.NoContent, (await client.SendAsync(valid)).StatusCode);
    }

    [Fact]
    public async Task Valid_proof_reaches_the_actual_admin_application_boundary()
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);
        var token = await BootstrapAsync(client);
        using var request = new HttpRequestMessage(HttpMethod.Post, RotatePath);
        request.Headers.Add(PlatformHttpSecurity.HeaderName, token);

        Assert.Equal(HttpStatusCode.NotFound, (await client.SendAsync(request)).StatusCode);
        Assert.Equal(1, app.Services.GetRequiredService<RecordingCredentialLifecycle>().Calls);
    }

    [Theory]
    [InlineData(false, HttpStatusCode.Unauthorized)]
    [InlineData(true, HttpStatusCode.Forbidden)]
    public async Task Authorization_still_rejects_missing_or_non_admin_identity(bool signIn, HttpStatusCode expected)
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        if (signIn) await SignInAsync(client, "user");
        Assert.Equal(expected, (await client.PostAsync(RotatePath, null)).StatusCode);
        Assert.Equal(0, app.Services.GetRequiredService<RecordingCredentialLifecycle>().Calls);
    }

    [Fact]
    public async Task An_authorization_header_cannot_bypass_cookie_protection_or_leak_validation_details()
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);
        client.DefaultRequestHeaders.Add("Authorization", "Bearer untrusted-test-value");
        client.DefaultRequestHeaders.Add(PlatformHttpSecurity.HeaderName, "invalid-test-proof");
        var response = await client.PostAsync(RotatePath, null);
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("antiforgery_validation_failed", body);
        Assert.DoesNotContain("invalid-test-proof", body);
        Assert.DoesNotContain("untrusted-test-value", body);
        Assert.DoesNotContain("Exception", body);
        Assert.DoesNotContain("stack", body, StringComparison.OrdinalIgnoreCase);
        Assert.True(response.Headers.CacheControl?.NoStore);
    }

    [Theory]
    [InlineData("bearer")]
    [InlineData("device")]
    [InlineData("machine")]
    public async Task Real_non_cookie_handlers_do_not_require_CSRF_even_with_an_ambient_browser_cookie(string mode)
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);
        AddCredentials(client, mode);
        Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsync($"/test/{mode}", null)).StatusCode);
    }

    [Theory]
    [InlineData("GET")]
    [InlineData("HEAD")]
    [InlineData("OPTIONS")]
    public async Task Read_only_requests_do_not_require_mutation_proof(string method)
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);
        Assert.Equal(HttpStatusCode.NoContent,
            (await client.SendAsync(new HttpRequestMessage(new HttpMethod(method), "/test/browser"))).StatusCode);
    }

    [Fact]
    public async Task Browser_SignalR_negotiation_remains_functional()
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        await SignInAsync(client);
        Assert.Equal(HttpStatusCode.OK,
            (await client.PostAsync("/test/hub/negotiate?negotiateVersion=1", null)).StatusCode);
    }

    [Fact]
    public async Task Proof_is_bound_to_the_authenticated_identity_and_both_cookies_remain_HttpOnly_and_secure()
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        var anonymousToken = await BootstrapAsync(client);
        var authentication = await SignInAsync(client);
        var cookies = authentication.Headers.GetValues("Set-Cookie").ToArray();
        Assert.Contains(cookies, cookie => cookie.StartsWith("__Host-BotGlobal.Admin=")
            && cookie.Contains("httponly") && cookie.Contains("secure") && cookie.Contains("samesite=none")
            && cookie.Contains("path=/") && !cookie.Contains("domain=", StringComparison.OrdinalIgnoreCase));

        using var stale = new HttpRequestMessage(HttpMethod.Post, RotatePath);
        stale.Headers.Add(PlatformHttpSecurity.HeaderName, anonymousToken);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.SendAsync(stale)).StatusCode);
        var response = await client.GetAsync(PlatformHttpSecurity.TokenPath);
        Assert.True(response.Headers.CacheControl?.NoStore);
        // A fresh client must receive a host-only, HttpOnly antiforgery cookie.
        var fresh = await Client(app).GetAsync(PlatformHttpSecurity.TokenPath);
        Assert.Contains(fresh.Headers.GetValues("Set-Cookie"), cookie =>
            cookie.StartsWith("__Host-BotGlobal.Antiforgery=") && cookie.Contains("httponly")
            && cookie.Contains("secure") && cookie.Contains("samesite=none")
            && !cookie.Contains("domain=", StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public async Task Development_HTTP_proxy_can_bootstrap_without_weakening_production_cookie_options()
    {
        await using var app = await CreateAppAsync(Environments.Development);
        var client = app.GetTestClient();
        var response = await client.GetAsync(PlatformHttpSecurity.TokenPath);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains(response.Headers.GetValues("Set-Cookie"), cookie =>
            cookie.StartsWith("BotGlobal.Antiforgery.Development=") && cookie.Contains("httponly")
            && cookie.Contains("samesite=lax"));
    }

    [Theory]
    [InlineData("https://frontend.example.test", true)]
    [InlineData("https://untrusted.example.test", false)]
    public async Task Bootstrap_is_readable_cross_origin_only_by_the_configured_frontend(string origin, bool allowed)
    {
        await using var app = await CreateAppAsync();
        var client = Client(app);
        client.DefaultRequestHeaders.Add("Origin", origin);
        var response = await client.GetAsync(PlatformHttpSecurity.TokenPath);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(allowed, response.Headers.Contains("Access-Control-Allow-Origin"));
        if (allowed)
        {
            Assert.Equal(origin, Assert.Single(response.Headers.GetValues("Access-Control-Allow-Origin")));
            Assert.Equal("true", Assert.Single(response.Headers.GetValues("Access-Control-Allow-Credentials")));
        }
    }

    [Fact]
    public async Task Request_proof_cannot_be_reused_with_a_different_antiforgery_cookie()
    {
        await using var app = await CreateAppAsync();
        var first = Client(app);
        var second = Client(app);
        await SignInAsync(first);
        await SignInAsync(second);
        var firstToken = await BootstrapAsync(first);
        await BootstrapAsync(second);
        second.DefaultRequestHeaders.Add(PlatformHttpSecurity.HeaderName, firstToken);
        Assert.Equal(HttpStatusCode.BadRequest, (await second.PostAsync(RotatePath, null)).StatusCode);
        Assert.Equal(0, app.Services.GetRequiredService<RecordingCredentialLifecycle>().Calls);
    }

    [Theory]
    [InlineData("Production", false)]
    [InlineData("Production", true)]
    [InlineData("Staging", true)]
    [InlineData("Test", true)]
    public async Task Diagnostic_route_is_absent_outside_Development_for_any_recipient_or_raw_destination(
        string environment, bool authenticated)
    {
        await using var app = await CreateAppAsync(environment);
        var client = Client(app);
        if (authenticated) await SignInAsync(client);
        var response = await client.PostAsJsonAsync(DiagnosticPath, new
        {
            targetUserId = "foreign-application-test-recipient",
            applicationId = Guid.NewGuid(),
            registrationToken = "not-a-real-fcm-token",
            text = "Test only"
        });
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Equal(0, app.Services.GetRequiredService<RecordingDiagnosticDelivery>().Calls);
    }

    [Fact]
    public async Task Development_diagnostic_still_requires_authentication_and_browser_proof()
    {
        await using var app = await CreateAppAsync(Environments.Development);
        var client = Client(app);
        var payload = new { targetUserId = "local-test-recipient", text = "Test only" };
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync(DiagnosticPath, payload)).StatusCode);
        await SignInAsync(client);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(DiagnosticPath, payload)).StatusCode);
        client.DefaultRequestHeaders.Add(PlatformHttpSecurity.HeaderName, await BootstrapAsync(client));
        Assert.Equal(HttpStatusCode.OK, (await client.PostAsJsonAsync(DiagnosticPath, payload)).StatusCode);
        Assert.Equal(1, app.Services.GetRequiredService<RecordingDiagnosticDelivery>().Calls);
    }

    [Fact]
    public async Task Direct_semantic_endpoint_remains_machine_scoped_and_available_when_campaign_worker_is_disabled()
    {
        await using var app = await CreateAppAsync();
        Assert.DoesNotContain(app.Services.GetServices<IHostedService>(), service =>
            service is NotificationCampaignBackgroundService);
        var client = Client(app);
        AddCredentials(client, "machine");
        var foreign = Guid.NewGuid();
        var response = await client.PostAsJsonAsync("/api/mobile-notifications", new
        {
            platformClientId = foreign,
            applicationId = foreign,
            recipientExternalSubjectId = "test-subject",
            titleAr = "اختبار", titleEn = "Test", bodyAr = "اختبار", bodyEn = "Test",
            type = "general", priority = 1,
            registrationToken = "not-a-real-fcm-token"
        });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(TestActors.ApplicationId, app.Services.GetRequiredService<RecordingDirectNotification>().ApplicationId);
    }

    private static async Task<WebApplication> CreateAppAsync(
        string environment = "Production", bool includeProtection = true)
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { EnvironmentName = environment });
        builder.Configuration.Sources.Clear();
        builder.Configuration.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["ConnectionStrings:Identity"] = "Server=localhost;Database=SecurityTests;Integrated Security=True",
            ["ConnectionStrings:PlatformClients"] = "Server=localhost;Database=SecurityTests;Integrated Security=True",
            ["ConnectionStrings:Notifications"] = "Server=localhost;Database=SecurityTests;Integrated Security=True",
            ["Notifications:Worker:Enabled"] = "false"
        });
        builder.WebHost.UseTestServer();
        builder.Logging.ClearProviders();
        builder.Services.AddDataProtection().UseEphemeralDataProtectionProvider();
        builder.Services.AddIdentityModule(builder.Configuration);
        builder.Services.AddPlatformClientsModule(builder.Configuration);
        builder.Services.AddNotificationsModule(builder.Configuration);
        // No audience read or provider dispatch is permitted in this HTTP test host.
        builder.Services.AddSingleton<IMobileBroadcastAudienceReader, UnusedCampaignDependencies>();
        builder.Services.AddSingleton<IMobileNotificationTransport, UnusedCampaignDependencies>();
        builder.Services.AddPlatformHttpSecurity();
        builder.Services.AddProblemDetails();
        builder.Services.AddSignalR();
        builder.Services.AddCors(options => options.AddDefaultPolicy(policy => policy
            .WithOrigins("https://frontend.example.test")
            .AllowAnyHeader().AllowAnyMethod().AllowCredentials()));
        builder.Services.AddAuthentication()
            .AddScheme<AuthenticationSchemeOptions, MobileDeviceAuthenticationHandler>(MobileDeviceAuthenticationDefaults.Scheme, _ => { });
        builder.Services.AddSingleton<IMobileApplicationSessionAuthenticator, TestActors>();
        builder.Services.AddSingleton<IMobileDeviceAuthenticator, TestActors>();
        builder.Services.AddSingleton<IPlatformClientAuthenticator, TestActors>();
        builder.Services.AddSingleton<RecordingCredentialLifecycle>();
        builder.Services.AddSingleton<IPlatformClientCredentialLifecycleService>(sp => sp.GetRequiredService<RecordingCredentialLifecycle>());
        builder.Services.AddSingleton<RecordingDiagnosticDelivery>();
        builder.Services.AddSingleton<ICommunicationDelivery>(sp => sp.GetRequiredService<RecordingDiagnosticDelivery>());
        builder.Services.AddSingleton<RecordingDirectNotification>();
        builder.Services.AddSingleton<IMobileNotificationService>(sp => sp.GetRequiredService<RecordingDirectNotification>());
        var app = builder.Build();
        app.UseCors();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseRateLimiter();
        if (includeProtection) app.UsePlatformHttpSecurity();
        app.MapPlatformHttpSecurity();
        app.MapPlatformClientAdminEndpoints();
        app.MapCommunicationModule(new MobileNotificationMachineAuthorizationOptions(
            PlatformClientAuthenticationDefaults.ClientIdClaim, PlatformClientPolicies.Capability));
        app.MapPost("/test/sign-in/{kind}", async (string kind, HttpContext context) =>
        {
            var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, "22222222-2222-2222-2222-222222222222"), new(ClaimTypes.Name, "Test actor") };
            if (kind == "admin") claims.Add(new Claim(ClaimTypes.Role, "Administrator"));
            await context.SignInAsync(IdentityConstants.ApplicationScheme,
                new ClaimsPrincipal(new ClaimsIdentity(claims, IdentityConstants.ApplicationScheme)));
            return Results.NoContent();
        }).AllowAnonymous();
        app.MapMethods("/test/browser", ["GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE"],
            () => Results.NoContent()).RequireAuthorization("Administrator");
        app.MapPost("/test/bearer", () => Results.NoContent())
            .RequireAuthorization(ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb));
        app.MapPost("/test/device", () => Results.NoContent()).RequireAuthorization(new AuthorizationPolicyBuilder(
            MobileDeviceAuthenticationDefaults.Scheme).RequireAuthenticatedUser().Build());
        app.MapPost("/test/machine", () => Results.NoContent()).RequireAuthorization(PlatformClientPolicies.AuthenticatedClient());
        app.MapHub<TestBrowserHub>("/test/hub").RequireAuthorization("Administrator");
        await app.StartAsync();
        return app;
    }

    private static HttpClient Client(WebApplication app)
    {
        var client = app.GetTestClient();
        client.BaseAddress = new Uri("https://localhost");
        return client;
    }

    private static async Task<HttpResponseMessage> SignInAsync(HttpClient client, string kind = "admin")
    {
        var response = await client.PostAsync($"/test/sign-in/{kind}", null);
        response.EnsureSuccessStatusCode();
        StoreCookies(client, response);
        return response;
    }

    private static async Task<string> BootstrapAsync(HttpClient client)
    {
        var response = await client.GetAsync(PlatformHttpSecurity.TokenPath);
        response.EnsureSuccessStatusCode();
        StoreCookies(client, response);
        return (await response.Content.ReadFromJsonAsync<Dictionary<string, string>>())!["requestToken"];
    }

    private static void StoreCookies(HttpClient client, HttpResponseMessage response)
    {
        var values = new Dictionary<string, string>();
        if (client.DefaultRequestHeaders.TryGetValues("Cookie", out var existing))
            foreach (var cookie in string.Join(";", existing).Split(';', StringSplitOptions.TrimEntries))
                values[cookie.Split('=')[0]] = cookie;
        if (response.Headers.TryGetValues("Set-Cookie", out var received))
            foreach (var header in received)
            {
                var cookie = header.Split(';')[0];
                values[cookie.Split('=')[0]] = cookie;
            }
        client.DefaultRequestHeaders.Remove("Cookie");
        client.DefaultRequestHeaders.TryAddWithoutValidation("Cookie", string.Join("; ", values.Values));
    }

    private static void AddCredentials(HttpClient client, string mode)
    {
        if (mode == "machine")
        {
            client.DefaultRequestHeaders.Add(PlatformClientAuthenticationDefaults.ClientKeyHeader, "test-client");
            client.DefaultRequestHeaders.Add(PlatformClientAuthenticationDefaults.ClientSecretHeader, "test-client-secret");
        }
        else client.DefaultRequestHeaders.Add("Authorization",
            mode == "device" ? "Device test-device-credential" : "Bearer test-mobile-access");
    }

    public sealed class TestBrowserHub : Hub;

    private sealed class RecordingCredentialLifecycle : IPlatformClientCredentialLifecycleService
    {
        public int Calls { get; private set; }
        public Task<RotatedPlatformClientCredential> RotateAsync(Guid clientId, CancellationToken cancellationToken = default)
        {
            Calls++;
            throw new KeyNotFoundException("Test application boundary reached.");
        }
        public Task RevokeAsync(Guid clientId, Guid credentialId, CancellationToken cancellationToken = default) => throw new NotSupportedException();
    }

    private sealed class RecordingDiagnosticDelivery : ICommunicationDelivery
    {
        public int Calls { get; private set; }
        public Task<RealtimeTestMessage> SendTestMessageToUserAsync(string senderUserId, string targetUserId, string text, CancellationToken cancellationToken = default)
        {
            Calls++;
            return Task.FromResult(new RealtimeTestMessage("test-delivery", senderUserId, targetUserId, text, DateTimeOffset.UtcNow));
        }
    }

    private sealed class RecordingDirectNotification : IMobileNotificationService
    {
        public Guid ApplicationId { get; private set; }
        public Task<SendMobileNotificationResponse> SendAsync(Guid platformClientId, SendMobileNotificationRequest request, CancellationToken cancellationToken)
        {
            ApplicationId = platformClientId;
            return Task.FromResult(new SendMobileNotificationResponse("test-notification", request.RecipientExternalSubjectId, 1, "fcm-dispatched"));
        }
    }

    private sealed class UnusedCampaignDependencies : IMobileBroadcastAudienceReader, IMobileNotificationTransport
    {
        public Task<MobileBroadcastAudiencePreview> PreviewAsync(NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileBroadcastAudiencePage> ReadPageAsync(NotificationApplicationContext application,
            DateTimeOffset audienceAsOfUtc, Guid? afterDeviceId, int pageSize, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileBroadcastDeviceState> GetCurrentDeviceStateAsync(NotificationApplicationContext application,
            Guid deviceId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<MobileNotificationTransportOutcome> DispatchAsync(MobileNotificationTransportRequest request,
            CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class TestActors : IMobileDeviceAuthenticator, IMobileApplicationSessionAuthenticator, IPlatformClientAuthenticator
    {
        public static readonly Guid ApplicationId = Guid.Parse("33333333-3333-3333-3333-333333333333");
        Task<AuthenticatedMobileDevice?> IMobileDeviceAuthenticator.AuthenticateAsync(string credential, CancellationToken cancellationToken) =>
            Task.FromResult<AuthenticatedMobileDevice?>(credential == "test-device-credential"
                ? new(Guid.NewGuid(), ApplicationId, "test-subject") : null);
        Task<AuthenticatedApplicationSession?> IMobileApplicationSessionAuthenticator.AuthenticateAsync(string accessToken, CancellationToken cancellationToken) =>
            Task.FromResult<AuthenticatedApplicationSession?>(accessToken == "test-mobile-access"
                ? new(Guid.NewGuid(), new ApplicationIdentityDescriptor(Guid.NewGuid(), null, "test-subject", BotGlobalApplications.Nqrb, "Test actor", false)) : null);
        public Task<PlatformClientAuthenticationResult?> AuthenticateAsync(string clientKey, string clientSecret, DateTimeOffset utcNow, CancellationToken cancellationToken = default) =>
            Task.FromResult<PlatformClientAuthenticationResult?>(clientKey == "test-client" && clientSecret == "test-client-secret"
                ? new(ApplicationId, "test-client", [MobileNotificationEndpoints.SendCapability]) : null);
    }
}
