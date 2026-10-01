using System.Reflection;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Api.Controllers;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Tests.Api;

public sealed class DeviceRemovalEndpointTests
{
    [Fact]
    public async Task NonAdministratorIsForbiddenByTheConfiguredRemovalPolicy()
    {
        using var profile = new TemporaryProfile();
        await using var app = CreateFactory(profile.Path);
        using var scope = app.Services.CreateScope();
        var authorization = scope.ServiceProvider.GetRequiredService<IAuthorizationService>();
        var policies = scope.ServiceProvider.GetRequiredService<IAuthorizationPolicyProvider>();
        var policy = await policies.GetPolicyAsync(AuthorizationPolicies.DeviceAdministration);
        var operatorOnly = Principal(AuthorizationRoles.DeviceOperator);

        var result = await authorization.AuthorizeAsync(operatorOnly, null, policy!);

        Assert.False(result.Succeeded);
    }

    [Fact]
    public async Task AdministratorIsAuthorizedAndBothRemovalEndpointsRequireAdminPolicy()
    {
        using var profile = new TemporaryProfile();
        await using var app = CreateFactory(profile.Path);
        using var scope = app.Services.CreateScope();
        var authorization = scope.ServiceProvider.GetRequiredService<IAuthorizationService>();
        var policies = scope.ServiceProvider.GetRequiredService<IAuthorizationPolicyProvider>();
        var policy = await policies.GetPolicyAsync(AuthorizationPolicies.DeviceAdministration);
        var administrator = Principal(
            AuthorizationRoles.DeviceOperator,
            AuthorizationRoles.HubAdministrator);

        var result = await authorization.AuthorizeAsync(administrator, null, policy!);
        var remove = typeof(RemoteMonitoringController).GetMethod(
            nameof(RemoteMonitoringController.RemoveDeviceAsync));
        var impact = typeof(RemoteMonitoringController).GetMethod(
            nameof(RemoteMonitoringController.GetRemovalImpactAsync));

        Assert.True(result.Succeeded);
        Assert.Equal(
            AuthorizationPolicies.DeviceAdministration,
            remove!.GetCustomAttribute<AuthorizeAttribute>()!.Policy);
        Assert.Equal(
            AuthorizationPolicies.DeviceAdministration,
            impact!.GetCustomAttribute<AuthorizeAttribute>()!.Policy);
    }

    private static ClaimsPrincipal Principal(params string[] roles)
    {
        var claims = new List<Claim>
        {
            new("sub", "operator-test"),
            new(AuthenticationClaimNames.TokenType, AuthenticationTokenTypes.Operator),
        };
        claims.AddRange(roles.Select(role => new Claim("role", role)));
        return new ClaimsPrincipal(new ClaimsIdentity(claims, "test", "sub", "role"));
    }

    private static WebApplicationFactory<Program> CreateFactory(string profilePath) =>
        new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
        {
            builder.UseEnvironment("Development");
            builder.UseSetting("SentriCam:Home", profilePath);
            builder.UseSetting("SentriCam:FreshInstall", "true");
            builder.UseSetting("Authentication:Jwt:Issuer", "SentriCam.Removal.Tests");
            builder.UseSetting("Authentication:Jwt:Audience", "SentriCam.Removal.Clients");
            builder.UseSetting(
                "Authentication:Jwt:SigningKey",
                "device-removal-test-signing-key-at-least-32-characters");
        });

    private sealed class TemporaryProfile : IDisposable
    {
        public string Path { get; } = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(),
            $"sentricam-device-removal-{Guid.NewGuid():N}");

        public TemporaryProfile() => Directory.CreateDirectory(Path);

        public void Dispose()
        {
            if (Directory.Exists(Path)) Directory.Delete(Path, recursive: true);
        }
    }
}
