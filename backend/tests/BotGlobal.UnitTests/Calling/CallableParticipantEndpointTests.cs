using System.Net;
using System.Security.Claims;
using System.Text.Encodings.Web;
using System.Text.Json;
using BotGlobal.Calling;
using BotGlobal.Calling.Realtime;
using BotGlobal.Calling.Application;
using BotGlobal.Calling.Infrastructure;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.AspNetCore.Routing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
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
        var contacts = new RecordingNqrbContacts(
        [
            Participant(remoteMembershipId, "Remote participant")
        ]);
        await using var app = await CreateAppAsync(directory, contacts: contacts);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "nqrb");
        client.DefaultRequestHeaders.Add(
            "X-Test-Membership",
            currentMembershipId.ToString());

        var response = await client.GetAsync("/api/mobile/calling/participants?savedOnly=true");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Null(directory.ApplicationKey);
        Assert.Equal(currentMembershipId, contacts.OwnerMembershipId);
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
        await using var app = await CreateAppAsync(new RecordingDirectory([]),
            contacts: new RecordingNqrbContacts([]));
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
        var contacts = new RecordingNqrbContacts(
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
            new FixedReachabilityResolver(reachableMembershipId),
            contacts: contacts);
        var client = app.GetTestClient();
        Authenticate(client, currentMembershipId);

        var response = await client.GetAsync("/api/mobile/calling/participants?savedOnly=true");

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
    public async Task Nqrb_participants_return_saved_contacts_instead_of_global_directory()
    {
        var currentMembershipId = Guid.NewGuid();
        var savedContactId = Guid.NewGuid();
        var directory = new RecordingDirectory(
        [
            Participant(Guid.NewGuid(), "Global directory person")
        ]);
        var contacts = new RecordingNqrbContacts(
        [
            Participant(savedContactId, "Saved contact")
        ]);
        await using var app = await CreateAppAsync(directory, contacts: contacts);
        var client = app.GetTestClient();
        Authenticate(client, currentMembershipId);

        var response = await client.GetAsync("/api/mobile/calling/participants?savedOnly=true");

        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var participant = Assert.Single(json.RootElement.EnumerateArray());
        Assert.Equal(savedContactId, participant.GetProperty("membershipId").GetGuid());
        Assert.Equal("Saved contact", participant.GetProperty("displayName").GetString());
        Assert.Equal(currentMembershipId, contacts.OwnerMembershipId);
        Assert.Null(directory.CurrentMembershipId);
    }

    [Fact]
    public async Task Existing_nqrb_clients_without_saved_only_keep_their_callable_directory()
    {
        var currentMembershipId = Guid.NewGuid();
        var previousVersionContactId = Guid.NewGuid();
        var directory = new RecordingDirectory([Participant(previousVersionContactId, "Existing contact")]);
        var contacts = new RecordingNqrbContacts([]);
        await using var app = await CreateAppAsync(directory, contacts: contacts);
        var client = app.GetTestClient();
        Authenticate(client, currentMembershipId);

        var response = await client.GetAsync("/api/mobile/calling/participants");

        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal(previousVersionContactId,
            Assert.Single(json.RootElement.EnumerateArray()).GetProperty("membershipId").GetGuid());
        Assert.Equal(currentMembershipId, directory.CurrentMembershipId);
        Assert.Null(contacts.OwnerMembershipId);
    }

    [Fact]
    public async Task Non_nqrb_participants_keep_existing_directory_behavior()
    {
        var currentMembershipId = Guid.NewGuid();
        var globalParticipantId = Guid.NewGuid();
        var directory = new RecordingDirectory(
        [
            new CallingParticipantDescriptor(
                globalParticipantId,
                "lamma",
                "private-subject",
                "Global person",
                true)
        ]);
        var contacts = new RecordingNqrbContacts(
        [
            Participant(Guid.NewGuid(), "Saved NQRB contact")
        ]);
        await using var app = await CreateAppAsync(directory, contacts: contacts);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "lamma");
        client.DefaultRequestHeaders.Add("X-Test-Membership", currentMembershipId.ToString());

        var response = await client.GetAsync("/api/mobile/calling/participants");

        response.EnsureSuccessStatusCode();
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var participant = Assert.Single(json.RootElement.EnumerateArray());
        Assert.Equal(globalParticipantId, participant.GetProperty("membershipId").GetGuid());
        Assert.Equal(currentMembershipId, directory.CurrentMembershipId);
        Assert.Null(contacts.OwnerMembershipId);
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

        var response = await client.GetAsync("/api/mobile/calling/history?page=2&pageSize=10&filter=missed");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("nqrb", activity.ApplicationKey);
        Assert.Equal(membershipId, activity.MembershipId);
        Assert.Equal(2, activity.Page);
        Assert.Equal(10, activity.PageSize);
        Assert.Equal(CallHistoryFilter.Missed, activity.Filter);
    }

    [Fact]
    public async Task History_endpoint_rejects_undefined_numeric_filter_values()
    {
        var membershipId = Guid.NewGuid();
        var activity = new RecordingActivity();
        await using var app = await CreateAppAsync(new RecordingDirectory([]), activity: activity);
        var client = app.GetTestClient();
        Authenticate(client, membershipId);

        var response = await client.GetAsync("/api/mobile/calling/history?filter=999");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Null(activity.Filter);
    }

    [Fact]
    public async Task Nqrb_invite_endpoints_are_rate_limited()
    {
        await using var app = await CreateAppAsync(new RecordingDirectory([]));

        var inviteEndpoints = app.Services.GetRequiredService<EndpointDataSource>()
            .Endpoints
            .OfType<RouteEndpoint>()
            .Where(endpoint => endpoint.RoutePattern.RawText?.Contains("/contact-invites", StringComparison.Ordinal) == true)
            .ToArray();

        Assert.Equal(3, inviteEndpoints.Length);
        Assert.All(inviteEndpoints, endpoint =>
        {
            var rateLimit = Assert.IsType<EnableRateLimitingAttribute>(
                endpoint.Metadata.GetMetadata<EnableRateLimitingAttribute>());
            Assert.Equal(CallingModule.NqrbContactInviteRateLimitPolicy, rateLimit.PolicyName);
        });
    }

    [Fact]
    public async Task Nqrb_invite_create_rejects_wrong_application_before_service()
    {
        var contacts = new RecordingNqrbContacts([]);
        await using var app = await CreateAppAsync(new RecordingDirectory([]), contacts: contacts);
        var client = app.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Test-Authenticated", "true");
        client.DefaultRequestHeaders.Add("X-Test-Application", "lamma");
        client.DefaultRequestHeaders.Add("X-Test-Membership", Guid.NewGuid().ToString());

        var response = await client.PostAsync("/api/mobile/nqrb/contact-invites", null);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Null(contacts.InviteIssuerMembershipId);
    }

    [Fact]
    public async Task Nqrb_contact_book_rejects_guest_membership()
    {
        await using var app = await CreateAppAsync(new RecordingDirectory([]));
        var client = app.GetTestClient();
        Authenticate(client, Guid.NewGuid());
        client.DefaultRequestHeaders.Add("X-Test-Guest", "true");

        var list = await client.GetAsync("/api/mobile/nqrb/contacts");
        var search = await client.GetAsync("/api/mobile/nqrb/users/search?query=ab");
        var invite = await client.PostAsync("/api/mobile/nqrb/contact-invites", null);

        Assert.Equal(HttpStatusCode.Unauthorized, list.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, search.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, invite.StatusCode);
    }

    [Fact]
    public async Task Nqrb_invite_flows_through_authenticated_http_and_relational_contact_book()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        await using var db = new CallingDbContext(new DbContextOptionsBuilder<CallingDbContext>()
            .UseSqlite(connection).Options);
        await db.Database.EnsureCreatedAsync();
        var first = Guid.NewGuid();
        var second = Guid.NewGuid();
        var contacts = new NqrbContactBookService(db,
            new FixedNqrbAccountDirectory(
                new CallingAccountDescriptor(first, "First Person"),
                new CallingAccountDescriptor(second, "Second Person", "private-subject")),
            TimeProvider.System);
        await using var app = await CreateAppAsync(new RecordingDirectory([]), contacts: contacts);
        var firstClient = app.GetTestClient();
        var secondClient = app.GetTestClient();
        Authenticate(firstClient, first);
        Authenticate(secondClient, second);

        var search = await firstClient.GetAsync("/api/mobile/nqrb/users/search?query=Second");
        search.EnsureSuccessStatusCode();
        using (var result = JsonDocument.Parse(await search.Content.ReadAsStringAsync()))
        {
            Assert.Equal(second, result.RootElement.GetProperty("items")[0].GetProperty("membershipId").GetGuid());
            Assert.False(result.RootElement.GetProperty("items")[0].TryGetProperty("subjectId", out _));
        }

        var create = await firstClient.PostAsync("/api/mobile/nqrb/contact-invites", null);
        create.EnsureSuccessStatusCode();
        using var created = JsonDocument.Parse(await create.Content.ReadAsStringAsync());
        var code = created.RootElement.GetProperty("code").GetString();
        Assert.NotNull(code);
        var preview = await secondClient.GetAsync($"/api/mobile/nqrb/contact-invites/{code}/preview");
        preview.EnsureSuccessStatusCode();
        var accept = await secondClient.PostAsync($"/api/mobile/nqrb/contact-invites/{code}/accept", null);
        accept.EnsureSuccessStatusCode();

        var firstList = await firstClient.GetAsync("/api/mobile/nqrb/contacts");
        var secondList = await secondClient.GetAsync("/api/mobile/nqrb/contacts");
        using (var firstJson = JsonDocument.Parse(await firstList.Content.ReadAsStringAsync()))
        using (var secondJson = JsonDocument.Parse(await secondList.Content.ReadAsStringAsync()))
        {
            Assert.Equal(second, firstJson.RootElement.GetProperty("items")[0].GetProperty("membershipId").GetGuid());
            Assert.Equal(first, secondJson.RootElement.GetProperty("items")[0].GetProperty("membershipId").GetGuid());
        }

        var removed = await secondClient.DeleteAsync($"/api/mobile/nqrb/contacts/{first}");
        Assert.Equal(HttpStatusCode.NoContent, removed.StatusCode);
        using var afterRemove = JsonDocument.Parse(await (await firstClient.GetAsync("/api/mobile/nqrb/contacts"))
            .Content.ReadAsStringAsync());
        Assert.Equal(second, afterRemove.RootElement.GetProperty("items")[0].GetProperty("membershipId").GetGuid());
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
        ICallActivityService? activity = null,
        INqrbContactBookService? contacts = null)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.AddSignalR();
        builder.Services.AddSingleton<ICallingParticipantDirectory>(directory);
        builder.Services.AddSingleton(contacts ?? new RecordingNqrbContacts([]));
        if (sessions is not null) builder.Services.AddSingleton(sessions);
        if (reachability is not null) builder.Services.AddSingleton(reachability);
        if (activity is not null) builder.Services.AddSingleton(activity);
        builder.Services
            .AddAuthentication(ApplicationIdentityDefaults.Scheme)
            .AddScheme<AuthenticationSchemeOptions, TestAuthenticationHandler>(
                ApplicationIdentityDefaults.Scheme,
                _ => { });
        builder.Services.AddAuthorizationBuilder().AddPolicy(
            ApplicationIdentityPolicies.For(BotGlobalApplications.Nqrb),
            policy =>
            {
                policy.AddAuthenticationSchemes(ApplicationIdentityDefaults.Scheme);
                policy.RequireAuthenticatedUser();
                policy.RequireClaim(
                    ApplicationIdentityDefaults.ApplicationKeyClaim,
                    BotGlobalApplications.Nqrb);
            });
        builder.Services.AddRateLimiter(options =>
        {
            options.AddFixedWindowLimiter(CallingModule.NqrbContactInviteRateLimitPolicy, limiter =>
            {
                limiter.PermitLimit = 100;
                limiter.Window = TimeSpan.FromMinutes(1);
            });
            options.AddFixedWindowLimiter(CallingModule.NqrbContactSearchRateLimitPolicy, limiter =>
            {
                limiter.PermitLimit = 100;
                limiter.Window = TimeSpan.FromMinutes(1);
            });
        });
        var app = builder.Build();
        app.UseAuthentication();
        app.UseAuthorization();
        app.UseRateLimiter();
        app.MapCallingModule();
        await app.StartAsync();
        return app;
    }

    private sealed class RecordingNqrbContacts(IReadOnlyList<CallingParticipantDescriptor> participants)
        : INqrbContactBookService
    {
        public Guid? OwnerMembershipId { get; private set; }
        public Guid? InviteIssuerMembershipId { get; private set; }

        public Task<NqrbContactBookEntry?> FindAsync(
            Guid ownerMembershipId,
            Guid contactMembershipId,
            CancellationToken cancellationToken) =>
            Task.FromResult(participants.Where(item => item.MembershipId == contactMembershipId)
                .Select(item => new NqrbContactBookEntry(item.MembershipId, item.DisplayName))
                .FirstOrDefault());

        public Task<NqrbContactBookPage> ListAsync(
            Guid ownerMembershipId,
            int page,
            int pageSize,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactBookPage(
                participants.Select(item => new NqrbContactBookEntry(item.MembershipId, item.DisplayName)).ToArray(),
                page,
                pageSize,
                false));

        public Task<IReadOnlyList<CallingParticipantDescriptor>> ListCallableContactsAsync(
            Guid ownerMembershipId,
            CancellationToken cancellationToken)
        {
            OwnerMembershipId = ownerMembershipId;
            return Task.FromResult(participants);
        }

        public Task<CallingAccountSearchPage> SearchUsersAsync(
            Guid ownerMembershipId,
            string query,
            int page,
            int pageSize,
            CancellationToken cancellationToken) =>
            Task.FromResult(new CallingAccountSearchPage([], page, pageSize, false));

        public Task<NqrbContactAddResult> AddAsync(
            Guid ownerMembershipId,
            Guid contactMembershipId,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null));

        public Task<NqrbContactAddResult> AddFromCallHistoryAsync(
            Guid ownerMembershipId,
            Guid callId,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactAddResult(NqrbContactAddStatus.Unavailable, null));

        public Task RemoveAsync(
            Guid ownerMembershipId,
            Guid contactMembershipId,
            CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public Task<NqrbContactNicknameResult> UpdateNicknameAsync(
            Guid ownerMembershipId,
            Guid contactMembershipId,
            string? nickname,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactNicknameResult(NqrbContactNicknameStatus.Unavailable, null));

        public Task<NqrbContactInviteCreateResult> CreateInviteAsync(
            Guid issuerMembershipId,
            CancellationToken cancellationToken)
        {
            InviteIssuerMembershipId = issuerMembershipId;
            return Task.FromResult(new NqrbContactInviteCreateResult(NqrbContactInviteStatus.Unavailable, null));
        }

        public Task<NqrbContactInvitePreviewResult> PreviewInviteAsync(
            Guid recipientMembershipId,
            string code,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactInvitePreviewResult(NqrbContactInviteStatus.Invalid, null));

        public Task<NqrbContactInviteAcceptResult> AcceptInviteAsync(
            Guid recipientMembershipId,
            string code,
            CancellationToken cancellationToken) =>
            Task.FromResult(new NqrbContactInviteAcceptResult(NqrbContactInviteStatus.Invalid, null));
    }

    private sealed class FixedNqrbAccountDirectory(params CallingAccountDescriptor[] accounts)
        : ICallingAccountDirectory
    {
        public Task<CallingAccountDescriptor?> FindActiveNonGuestAsync(
            string applicationKey, Guid membershipId, CancellationToken cancellationToken) =>
            Task.FromResult(accounts.SingleOrDefault(item => item.MembershipId == membershipId));

        public Task<IReadOnlyList<CallingAccountDescriptor>> FindActiveNonGuestAsync(
            string applicationKey, IReadOnlyCollection<Guid> membershipIds, CancellationToken cancellationToken) =>
            Task.FromResult<IReadOnlyList<CallingAccountDescriptor>>(
                accounts.Where(item => membershipIds.Contains(item.MembershipId)).ToArray());

        public Task<CallingAccountSearchPage> SearchActiveNonGuestsAsync(
            string applicationKey, Guid currentMembershipId, string query, int page, int pageSize,
            CancellationToken cancellationToken)
        {
            var matches = accounts.Where(item => item.MembershipId != currentMembershipId &&
                item.DisplayName.Contains(query, StringComparison.OrdinalIgnoreCase)).ToArray();
            return Task.FromResult(new CallingAccountSearchPage(matches, page, pageSize, false));
        }
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
        public CallHistoryFilter? Filter { get; private set; }
        public Task<CallHistoryPage> ListAsync(string applicationKey, Guid membershipId, int page, int pageSize, CallHistoryFilter filter, CancellationToken cancellationToken)
        {
            ApplicationKey = applicationKey; MembershipId = membershipId; Page = page; PageSize = pageSize; Filter = filter;
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
                    Request.Headers["X-Test-Membership"].ToString()),
                new Claim(
                    ApplicationIdentityDefaults.GuestClaim,
                    string.Equals(
                        Request.Headers["X-Test-Guest"].ToString(),
                        "true",
                        StringComparison.OrdinalIgnoreCase) ? "true" : "false")
            };
            var principal = new ClaimsPrincipal(
                new ClaimsIdentity(claims, Scheme.Name));
            return Task.FromResult(
                AuthenticateResult.Success(
                    new AuthenticationTicket(principal, Scheme.Name)));
        }
    }
}
