using BotGlobal.Notifications.Application;
using BotGlobal.Notifications.Endpoints;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Net;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.UnitTests.Notifications;

public sealed class NotificationCampaignEndpointTests
{
    [Fact]
    public async Task Every_admin_route_requires_administrator_role()
    {
        await using var app = await CreateAppAsync();
        var routes = app.Services.GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Where(endpoint => endpoint.RoutePattern.RawText?.StartsWith(
                "/api/admin/notification-campaigns",
                StringComparison.Ordinal) == true)
            .ToArray();

        Assert.Equal(5, routes.Length);
        Assert.All(routes, route =>
        {
            var authorization = route.Metadata.GetOrderedMetadata<IAuthorizeData>();
            Assert.Contains(authorization, data =>
                data.Policy == "Administrator");
        });
    }

    [Theory]
    [InlineData("admin", HttpStatusCode.OK)]
    [InlineData("user", HttpStatusCode.Forbidden)]
    [InlineData("machine", HttpStatusCode.Forbidden)]
    public async Task Only_human_administrator_principal_can_use_admin_routes(
        string principalKind,
        HttpStatusCode expectedStatus)
    {
        await using var app = await CreateAppAsync(withAuthorization: true);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Principal", principalKind);

        var response = await client.GetAsync(
            $"/api/admin/notification-campaigns/audience-preview/{Guid.NewGuid()}");

        Assert.Equal(expectedStatus, response.StatusCode);
    }

    [Fact]
    public async Task Create_route_has_rate_limit_and_idempotency_header_is_not_in_body()
    {
        await using var app = await CreateAppAsync();
        var route = app.Services.GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Single(endpoint =>
                endpoint.RoutePattern.RawText == "/api/admin/notification-campaigns/"
                && endpoint.Metadata.GetRequiredMetadata<HttpMethodMetadata>()
                    .HttpMethods.Contains(HttpMethods.Post));

        Assert.NotNull(route.Metadata.GetMetadata<EnableRateLimitingAttribute>());
        Assert.DoesNotContain(
            typeof(CreateNotificationCampaignRequest).GetProperties(),
            property => property.Name == "IdempotencyKey");
    }

    [Fact]
    public void Safe_admin_dtos_do_not_expose_recipient_identifiers()
    {
        var forbidden = new[]
        {
            "MobileDeviceId",
            "InstallationId",
            "RegistrationToken",
            "ExternalSubjectId",
            "RecipientId"
        };

        foreach (var type in new[]
                 {
                     typeof(NotificationCampaignAcceptedResponse),
                     typeof(NotificationCampaignSummaryResponse),
                     typeof(NotificationCampaignPageResponse),
                     typeof(NotificationAudiencePreviewResponse)
                 })
        {
            var properties = type.GetProperties().Select(property => property.Name).ToArray();
            Assert.DoesNotContain(properties, property => forbidden.Contains(property));
        }
    }

    private static async Task<WebApplication> CreateAppAsync(
        bool withAuthorization = false)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSingleton<INotificationCampaignService>(
            new StubCampaignService());
        builder.Services.AddSingleton<IAdministratorDescriptorReader>(
            new StubAdministratorReader());

        if (withAuthorization)
        {
            builder.Services.AddAuthentication("Test")
                .AddScheme<AuthenticationSchemeOptions, TestAuthHandler>(
                    "Test",
                    _ => { });
            builder.Services.AddAuthorization(options =>
                options.AddPolicy(
                    "Administrator",
                    policy => policy.RequireRole("Administrator")));
        }
        builder.Services.AddRateLimiter(options => options.AddFixedWindowLimiter(
            "notification-campaign-admin-create",
            limiter =>
            {
                limiter.PermitLimit = 1;
                limiter.Window = TimeSpan.FromMinutes(1);
            }));

        var app = builder.Build();
        if (withAuthorization)
        {
            app.UseAuthentication();
            app.UseAuthorization();
        }
        app.MapNotificationCampaignAdminEndpoints();
        await app.StartAsync();
        return app;
    }

    private sealed class StubCampaignService : INotificationCampaignService
    {
        public Task<NotificationAudiencePreviewResponse> PreviewAudienceAsync(Guid platformClientId, CancellationToken cancellationToken) =>
            Task.FromResult(new NotificationAudiencePreviewResponse(
                platformClientId,
                "app",
                "Application",
                DateTimeOffset.UtcNow,
                1,
                1,
                1));
        public Task<NotificationCampaignAcceptedResponse> CreateAsync(CreateNotificationCampaignCommand command, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<NotificationCampaignPageResponse> ListAsync(NotificationCampaignListQuery query, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<NotificationCampaignSummaryResponse?> FindAsync(ApplicationAdministrationScope applicationScope, Guid campaignId, CancellationToken cancellationToken) => throw new NotSupportedException();
        public Task<NotificationCampaignSummaryResponse?> CancelAsync(ApplicationAdministrationScope applicationScope, Guid campaignId, CancellationToken cancellationToken) => throw new NotSupportedException();
    }

    private sealed class StubAdministratorReader
        : IAdministratorDescriptorReader
    {
        public Task<AdministratorDescriptor?> FindAsync(
            Guid userId,
            CancellationToken cancellationToken) =>
            Task.FromResult<AdministratorDescriptor?>(
                new AdministratorDescriptor(
                    userId,
                    "Human Administrator",
                    true));
    }

    private sealed class TestAuthHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(
            options,
            logger,
            encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync()
        {
            var kind = Request.Headers["X-Test-Principal"].ToString();
            var claims = new List<Claim>();

            if (kind is "admin" or "user")
            {
                claims.Add(new Claim(
                    ClaimTypes.NameIdentifier,
                    Guid.NewGuid().ToString()));
                claims.Add(new Claim(ClaimTypes.Name, "Human user"));
            }

            if (kind == "admin")
            {
                claims.Add(new Claim(ClaimTypes.Role, "Administrator"));
            }
            else if (kind == "machine")
            {
                claims.Add(new Claim("platform_client_id", Guid.NewGuid().ToString()));
            }

            var identity = new ClaimsIdentity(claims, Scheme.Name);
            var ticket = new AuthenticationTicket(
                new ClaimsPrincipal(identity),
                Scheme.Name);
            return Task.FromResult(AuthenticateResult.Success(ticket));
        }
    }
}
