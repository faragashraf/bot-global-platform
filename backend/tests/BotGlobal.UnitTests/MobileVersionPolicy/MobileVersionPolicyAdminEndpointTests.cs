using System.Net;
using System.Security.Claims;
using System.Text.Encodings.Web;
using BotGlobal.Identity.Application.MobileVersionPolicies;
using BotGlobal.Identity.Endpoints;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.MobileVersionPolicy;

public sealed class MobileVersionPolicyAdminEndpointTests
{
    [Fact]
    public async Task Every_admin_route_requires_administrator_policy()
    {
        await using var app = await CreateAppAsync();
        var routes = app.Services.GetServices<EndpointDataSource>()
            .SelectMany(source => source.Endpoints)
            .OfType<RouteEndpoint>()
            .Where(endpoint => endpoint.RoutePattern.RawText?.StartsWith(
                "/api/admin/mobile-version-policies",
                StringComparison.Ordinal) == true)
            .ToArray();

        Assert.Equal(2, routes.Length);
        Assert.All(routes, route =>
        {
            var authorization = route.Metadata.GetOrderedMetadata<IAuthorizeData>();
            Assert.Contains(authorization, data => data.Policy == "Administrator");
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

        var response = await client.GetAsync("/api/admin/mobile-version-policies/");

        Assert.Equal(expectedStatus, response.StatusCode);
    }

    private static async Task<WebApplication> CreateAppAsync(bool withAuthorization = false)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSingleton<IMobileVersionPolicyAdminService>(
            new StubMobileVersionPolicyAdminService());

        if (withAuthorization)
        {
            builder.Services.AddAuthentication("Test")
                .AddScheme<AuthenticationSchemeOptions, TestAuthHandler>("Test", _ => { });
            builder.Services.AddAuthorization(options =>
                options.AddPolicy("Administrator", policy => policy.RequireRole("Administrator")));
        }

        var app = builder.Build();
        if (withAuthorization)
        {
            app.UseAuthentication();
            app.UseAuthorization();
        }

        app.MapMobileVersionPolicyAdminEndpoints();
        await app.StartAsync();
        return app;
    }

    private sealed class StubMobileVersionPolicyAdminService : IMobileVersionPolicyAdminService
    {
        public Task<IReadOnlyList<MobileVersionPolicyAdminItem>> ListAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult<IReadOnlyList<MobileVersionPolicyAdminItem>>(Array.Empty<MobileVersionPolicyAdminItem>());

        public Task<MobileVersionPolicyAdminItem> UpsertAsync(
            string applicationKey,
            string platform,
            UpdateMobileVersionPolicyRequest request,
            MobileVersionPolicyEditor editor,
            CancellationToken cancellationToken = default) =>
            throw new NotSupportedException();
    }

    private sealed class TestAuthHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync()
        {
            var kind = Request.Headers["X-Test-Principal"].ToString();
            var claims = new List<Claim>();

            if (kind is "admin" or "user")
            {
                claims.Add(new Claim(ClaimTypes.NameIdentifier, Guid.NewGuid().ToString()));
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
            return Task.FromResult(AuthenticateResult.Success(
                new AuthenticationTicket(new ClaimsPrincipal(identity), Scheme.Name)));
        }
    }
}
