using System.Net;
using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using BotGlobal.Calling;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Calling;

public sealed class CallableParticipantEndpointTests
{
    [Fact]
    public async Task Authenticated_request_derives_application_and_current_membership_from_session()
    {
        var currentMembershipId = Guid.NewGuid();
        var remoteMembershipId = Guid.NewGuid();
        var directory = new RecordingDirectory(
        [
            new CallingParticipantDescriptor(
                remoteMembershipId,
                "nqrb",
                "private-subject",
                "Remote participant",
                true)
        ]);
        await using var app = await CreateAppAsync(directory);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "nqrb");
        client.DefaultRequestHeaders.Add(
            "X-Test-Membership",
            currentMembershipId.ToString());

        var response = await client.GetAsync("/api/mobile/calling/participants");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("nqrb", directory.ApplicationKey);
        Assert.Equal(currentMembershipId, directory.CurrentMembershipId);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var participant = Assert.Single(json.RootElement.EnumerateArray());
        Assert.Equal(
            remoteMembershipId,
            participant.GetProperty("membershipId").GetGuid());
        Assert.Equal(
            "Remote participant",
            participant.GetProperty("displayName").GetString());
        Assert.False(participant.TryGetProperty("subjectId", out _));
        Assert.False(participant.TryGetProperty("applicationKey", out _));
    }

    [Fact]
    public async Task Empty_directory_is_a_valid_authenticated_response()
    {
        await using var app = await CreateAppAsync(new RecordingDirectory([]));
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "nqrb");
        client.DefaultRequestHeaders.Add(
            "X-Test-Membership",
            Guid.NewGuid().ToString());

        var response = await client.GetAsync("/api/mobile/calling/participants");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Empty(json.RootElement.EnumerateArray());
    }

    [Fact]
    public async Task Unauthenticated_request_is_rejected()
    {
        var directory = new RecordingDirectory([]);
        await using var app = await CreateAppAsync(directory);

        var response = await app.GetTestClient()
            .GetAsync("/api/mobile/calling/participants");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Null(directory.ApplicationKey);
    }

    private static async Task<WebApplication> CreateAppAsync(
        RecordingDirectory directory)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSignalR();
        builder.Services.AddSingleton<ICallingParticipantDirectory>(directory);
        builder.Services
            .AddAuthentication(ApplicationIdentityDefaults.Scheme)
            .AddScheme<AuthenticationSchemeOptions, TestAuthenticationHandler>(
                ApplicationIdentityDefaults.Scheme,
                _ => { });
        builder.Services.AddAuthorization();
        var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.MapCallingModule();
        await app.StartAsync();
        return app;
    }

    private sealed class RecordingDirectory(
        IReadOnlyList<CallingParticipantDescriptor> participants)
        : ICallingParticipantDirectory
    {
        public string? ApplicationKey { get; private set; }
        public Guid? CurrentMembershipId { get; private set; }

        public Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableAsync(
            string applicationKey,
            Guid currentMembershipId,
            CancellationToken cancellationToken)
        {
            ApplicationKey = applicationKey;
            CurrentMembershipId = currentMembershipId;
            return Task.FromResult(participants);
        }

        public Task<CallingParticipantDescriptor?> FindAsync(
            string applicationKey,
            Guid membershipId,
            CancellationToken cancellationToken) =>
            Task.FromResult<CallingParticipantDescriptor?>(null);
    }

    private sealed class TestAuthenticationHandler(
        IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger,
        UrlEncoder encoder)
        : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync()
        {
            if (!Request.Headers.TryGetValue("X-Test-Authenticated", out var value) ||
                value != "true")
            {
                return Task.FromResult(AuthenticateResult.NoResult());
            }

            var claims = new[]
            {
                new Claim(
                    ApplicationIdentityDefaults.ApplicationKeyClaim,
                    Request.Headers["X-Test-Application"].ToString()),
                new Claim(
                    ApplicationIdentityDefaults.MembershipIdClaim,
                    Request.Headers["X-Test-Membership"].ToString())
            };
            var principal = new ClaimsPrincipal(
                new ClaimsIdentity(claims, Scheme.Name));
            return Task.FromResult(
                AuthenticateResult.Success(
                    new AuthenticationTicket(principal, Scheme.Name)));
        }
    }
}
