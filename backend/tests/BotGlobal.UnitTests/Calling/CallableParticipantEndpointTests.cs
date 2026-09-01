using System.Net;
using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using BotGlobal.Calling;
using BotGlobal.Calling.Realtime;
using BotGlobal.Calling.Application;
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
    public async Task Availability_combines_live_calling_presence_and_push_reachability()
    {
        var currentMembershipId = Guid.NewGuid();
        var onlineMembershipId = Guid.NewGuid();
        var reachableMembershipId = Guid.NewGuid();
        var offlineMembershipId = Guid.NewGuid();
        var directory = new RecordingDirectory(
        [
            Participant(onlineMembershipId, "Online"),
            Participant(reachableMembershipId, "Reachable"),
            Participant(offlineMembershipId, "Offline")
        ]);
        var sessions = new CallSessionRegistry();
        sessions.Connected("online-1", Identity(onlineMembershipId, "nqrb"));
        sessions.Connected("online-other-device", Identity(onlineMembershipId, "nqrb"));
        sessions.Connected("cross-app", Identity(offlineMembershipId, "other-app"));
        await using var app = await CreateAppAsync(
            directory,
            sessions,
            new FixedReachabilityResolver(reachableMembershipId));
        var client = app.GetTestClient();
        Authenticate(client, currentMembershipId);

        var response = await client.GetAsync("/api/mobile/calling/participants");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var availability = json.RootElement.EnumerateArray().ToDictionary(
            item => item.GetProperty("membershipId").GetGuid(),
            item => item.GetProperty("availability").GetString());
        Assert.Equal("Online", availability[onlineMembershipId]);
        Assert.Equal("Reachable", availability[reachableMembershipId]);
        Assert.Equal("Offline", availability[offlineMembershipId]);
    }

    [Fact]
    public void Presence_stays_online_until_the_last_same_application_connection_leaves()
    {
        var membershipId = Guid.NewGuid();
        var sessions = new CallSessionRegistry();
        sessions.Connected("device-a", Identity(membershipId, "nqrb"));
        sessions.Connected("device-b", Identity(membershipId, "nqrb"));

        sessions.Disconnected("device-a");
        Assert.True(sessions.IsOnline(membershipId, "nqrb"));

        sessions.Disconnected("device-b");
        Assert.False(sessions.IsOnline(membershipId, "nqrb"));
    }

    [Fact]
    public async Task History_endpoint_derives_application_and_membership_from_authenticated_session()
    {
        var membershipId = Guid.NewGuid();
        var activity = new RecordingActivity();
        await using var app = await CreateAppAsync(new RecordingDirectory([]), activity: activity);
        var client = app.GetTestClient();
        Authenticate(client, membershipId);

        var response = await client.GetAsync("/api/mobile/calling/history?page=2&pageSize=10");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("nqrb", activity.ApplicationKey);
        Assert.Equal(membershipId, activity.MembershipId);
        Assert.Equal(2, activity.Page);
        Assert.Equal(10, activity.PageSize);
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
        RecordingDirectory directory,
        CallSessionRegistry? sessions = null,
        ICallingReachabilityResolver? reachability = null,
        ICallActivityService? activity = null)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSignalR();
        builder.Services.AddSingleton<ICallingParticipantDirectory>(directory);
        if (sessions is not null) builder.Services.AddSingleton(sessions);
        if (reachability is not null) builder.Services.AddSingleton(reachability);
        if (activity is not null) builder.Services.AddSingleton(activity);
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

    private static CallingParticipantDescriptor Participant(Guid id, string name) =>
        new(id, "nqrb", $"subject-{id:N}", name, true);

    private static ApplicationIdentityDescriptor Identity(Guid id, string applicationKey) =>
        new(id, Guid.NewGuid(), $"subject-{id:N}", applicationKey, "Participant", false);

    private static void Authenticate(HttpClient client, Guid membershipId)
    {
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "nqrb");
        client.DefaultRequestHeaders.Add("X-Test-Membership", membershipId.ToString());
    }

    private sealed class FixedReachabilityResolver(params Guid[] reachable) : ICallingReachabilityResolver
    {
        public Task<IReadOnlySet<Guid>> FindReachableMembershipsAsync(
            string applicationKey,
            IReadOnlyCollection<CallingParticipantDescriptor> participants,
            CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlySet<Guid>>(reachable.ToHashSet());
    }

    private sealed class RecordingActivity : ICallActivityService
    {
        public string? ApplicationKey { get; private set; }
        public Guid? MembershipId { get; private set; }
        public int? Page { get; private set; }
        public int? PageSize { get; private set; }
        public Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CancellationToken cancellationToken)
        {
            ApplicationKey = applicationKey; MembershipId = membershipId; Page = page; PageSize = pageSize;
            return Task.FromResult(new CallHistoryPage([], page, pageSize, false));
        }
        public Task StartAsync(CallSessionRegistry.Session session, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task AnswerAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task JoinedAsync(CallSessionRegistry.Session session, Guid membershipId, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task FinishAsync(CallSessionRegistry.Session session, DateTimeOffset at, CancellationToken cancellationToken) => Task.CompletedTask;
        public Task<CallHistoryDetail?> DetailAsync(string applicationKey, Guid membershipId, Guid callId, CancellationToken cancellationToken) => Task.FromResult<CallHistoryDetail?>(null);
        public Task<FinalizeUsageResult> FinalizeUsageAsync(string applicationKey, Guid membershipId, Guid callId, UsageSummary usage, CancellationToken cancellationToken) => Task.FromResult(new FinalizeUsageResult(true, false, false, null));
        public Task<UsagePeriodView> CurrentPeriodAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => Task.FromResult(Period());
        public Task<UsagePeriodView> ResetAsync(string applicationKey, Guid membershipId, CancellationToken cancellationToken) => Task.FromResult(Period());
        public Task<UsagePeriodView> ScheduleResetAsync(string applicationKey, Guid membershipId, DateTime localDateTime, string timeZoneId, CancellationToken cancellationToken) => Task.FromResult(Period());
        private static UsagePeriodView Period() => new(Guid.NewGuid(), DateTimeOffset.UtcNow, null, 0, 0, null, null);
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
