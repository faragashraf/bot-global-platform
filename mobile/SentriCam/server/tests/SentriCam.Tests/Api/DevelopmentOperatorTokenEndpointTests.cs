using System.Globalization;
using System.IdentityModel.Tokens.Jwt;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Monitoring;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Monitoring;
using SentriCam.Domain.Devices;
using SentriCam.BuildingBlocks.Authentication;

namespace SentriCam.Tests.Api;

public sealed class DevelopmentOperatorTokenEndpointTests
{
    [Fact]
    public async Task DevelopmentEndpointIssuesOperatorTokenWithExpectedClaimsAndExpiry()
    {
        await using var app = CreateFactory("Development", operatorTokenMinutes: 10);
        var client = app.CreateClient();
        var cancellation = TestContext.Current.CancellationToken;

        var response = await client.PostAsync("/api/v1/development/operator-token", null, cancellation);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var tokenResponse = await response.Content.ReadFromJsonAsync<OperatorTokenResponse>(cancellationToken: cancellation);
        Assert.NotNull(tokenResponse);
        Assert.Equal("Bearer", tokenResponse!.TokenType);
        var token = new JwtSecurityTokenHandler().ReadJwtToken(tokenResponse.AccessToken);

        Assert.Equal("SentriCam.Server.Tests", token.Issuer);
        Assert.Contains("SentriCam.TestClients", token.Audiences);
        Assert.Equal("sentricam-dev-operator", token.Subject);
        Assert.Contains(
            token.Claims,
            claim => claim.Type == "role" && claim.Value == AuthorizationRoles.DeviceOperator);
        Assert.Contains(
            token.Claims,
            claim => claim.Type == AuthenticationClaimNames.TokenType
                && claim.Value == AuthenticationTokenTypes.Operator);
    }

    [Fact]
    public async Task DevelopmentEndpointIsNotAvailableInProduction()
    {
        await using var app = CreateFactory("Production");
        var client = app.CreateClient();
        var cancellation = TestContext.Current.CancellationToken;

        var response = await client.PostAsync("/api/v1/development/operator-token", null, cancellation);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task DeviceTokenCannotAccessOperatorEndpoints()
    {
        await using var app = CreateFactory("Development", 10);
        var client = app.CreateClient();
        await using var scope = app.Services.CreateAsyncScope();
        var cancellation = TestContext.Current.CancellationToken;
        var tokenIssuer = scope.ServiceProvider.GetRequiredService<IDeviceAccessTokenIssuer>();
        var deviceId = DeviceId.From(Guid.Parse("11111111-1111-1111-1111-111111111111"));
        var token = tokenIssuer.Issue(deviceId, "dev-install", DateTimeOffset.UtcNow);

        var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/monitoring/devices");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token.Value);

        var response = await client.SendAsync(request, cancellation);
        Assert.True(
            response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden,
            $"unexpected status {response.StatusCode}");
    }

    [Fact]
    public async Task OperatorTokenCanAccessMonitoringDevices()
    {
        await using var app = CreateFactory("Development", 10);
        var client = app.CreateClient();
        var cancellation = TestContext.Current.CancellationToken;

        var tokenResponse = await client
            .PostAsync("/api/v1/development/operator-token", null, cancellation);
        Assert.Equal(HttpStatusCode.OK, tokenResponse.StatusCode);
        var tokenPayload = await tokenResponse.Content.ReadFromJsonAsync<OperatorTokenResponse>(cancellationToken: cancellation);
        Assert.NotNull(tokenPayload);

        var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/monitoring/devices");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", tokenPayload!.AccessToken);
        var monitoringResponse = await client.SendAsync(request, cancellation);

        Assert.Equal(HttpStatusCode.OK, monitoringResponse.StatusCode);
        var devices = await monitoringResponse.Content.ReadFromJsonAsync<List<DeviceLiveState>>(cancellationToken: cancellation);
        Assert.NotNull(devices);
    }

    [Fact]
    public async Task DevelopmentOperatorTokenExpirationMatchesConfiguration()
    {
        await using var app = CreateFactory("Development", operatorTokenMinutes: 2);
        var client = app.CreateClient();
        var cancellation = TestContext.Current.CancellationToken;

        var response = await client.PostAsync("/api/v1/development/operator-token", null, cancellation);
        var tokenResponse = await response.Content.ReadFromJsonAsync<OperatorTokenResponse>(cancellationToken: cancellation);
        var token = new JwtSecurityTokenHandler().ReadJwtToken(tokenResponse!.AccessToken);
        Assert.NotNull(tokenResponse);
        var issuedAtSeconds = long.Parse(
            token.Claims.Single(claim => claim.Type == JwtRegisteredClaimNames.Iat).Value,
            CultureInfo.InvariantCulture);
        var issuedAt = DateTimeOffset.FromUnixTimeSeconds(issuedAtSeconds);

        Assert.InRange(
            tokenResponse!.ExpiresAtUtc,
            issuedAt.AddMinutes(1).AddSeconds(30),
            issuedAt.AddMinutes(2).AddSeconds(30));
    }

    private static WebApplicationFactory<Program> CreateFactory(
        string environment,
        int? operatorTokenMinutes = null)
    {
        return new WebApplicationFactory<Program>()
            .WithWebHostBuilder(builder =>
            {
                builder.UseEnvironment(environment);
                builder.UseSetting("ConnectionStrings:SentriCam", "Server=localhost;Database=sentricam_dev_tokens;");
                builder.UseSetting("Authentication:Jwt:Issuer", "SentriCam.Server.Tests");
                builder.UseSetting("Authentication:Jwt:Audience", "SentriCam.TestClients");
                builder.UseSetting(
                    "Authentication:Jwt:SigningKey",
                    "test-only-signing-key-with-at-least-thirty-two-chars");
                builder.UseSetting(
                    "Authentication:Jwt:DevelopmentOperatorTokenLifetimeMinutes",
                    (operatorTokenMinutes ?? 10).ToString(CultureInfo.InvariantCulture));
                builder.ConfigureServices(services =>
                {
                    services.AddSingleton<IRemoteMonitoringService, FakeRemoteMonitoringService>();
                });
            });
    }

    private sealed class FakeRemoteMonitoringService : IRemoteMonitoringService
    {
        public Task<IReadOnlyList<DeviceLiveState>> GetDevicesAsync(
            CancellationToken cancellationToken = default) =>
            Task.FromResult((IReadOnlyList<DeviceLiveState>)Array.Empty<DeviceLiveState>());

        public Task<DeviceMonitoringDetails> GetDeviceAsync(
            Guid deviceId,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();

        public Task<RemoteCommandView> SubmitAsync(
            IDeviceControlCommand command,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();

        public Task CompleteAsync(
            Guid authenticatedDeviceId,
            DeviceCommandResultEnvelope result,
            CancellationToken cancellationToken = default) =>
            throw new NotImplementedException();

        public Task<int> ExpireCommandsAsync(CancellationToken cancellationToken = default) =>
            Task.FromResult(0);
    }
}
