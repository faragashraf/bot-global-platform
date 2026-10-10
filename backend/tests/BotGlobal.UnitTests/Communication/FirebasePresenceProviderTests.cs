using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using BotGlobal.Communication.Application.Presence;
using BotGlobal.Communication.Infrastructure.Presence;
using BotGlobal.Contracts.Communication;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Communication;

public sealed class FirebasePresenceProviderTests
{
    private static readonly DateTimeOffset Now = DateTimeOffset.Parse("2026-10-09T12:00:00Z");

    [Fact]
    public async Task Reads_only_the_server_indexed_session_and_exact_connection_path()
    {
        var authority = Authority();
        var handler = new SequenceHandler(
            Control(authority),
            Json(JsonSerializer.Serialize(new
            {
                connectionA = new { state = "connected", observedAt = Now.ToUnixTimeMilliseconds() },
            })));
        var provider = Provider(handler);

        var result = await provider.ReadSessionAsync(authority, default);

        Assert.True(result.ControlFound);
        Assert.True(result.Complete);
        Assert.Equal(PresenceEvidenceState.Connected, Assert.Single(result.Connections).State);
        Assert.Collection(handler.Requests,
            request => Assert.Equal("https://presence.example/__presenceControls/bySession/11111111111111111111111111111111/22222222222222222222222222222222.json", request.Uri),
            request => Assert.Equal("https://presence.example/presenceConnections/uidA/leaseA.json", request.Uri));
        Assert.All(handler.Requests, request => Assert.Equal("Bearer admin-token", request.Authorization));
    }

    [Fact]
    public async Task Redirects_are_rejected_without_following_the_target()
    {
        var redirect = new HttpResponseMessage(HttpStatusCode.TemporaryRedirect)
        {
            Headers = { Location = new Uri("https://attacker.example/capture") },
        };
        var handler = new SequenceHandler(redirect);
        var provider = Provider(handler);

        await Assert.ThrowsAsync<HttpRequestException>(() => provider.ReadSessionAsync(Authority(), default));

        Assert.Single(handler.Requests);
        Assert.Equal("presence.example", new Uri(handler.Requests[0].Uri).Host);
    }

    [Fact]
    public async Task Malformed_control_or_connection_data_never_becomes_disconnected()
    {
        var authority = Authority();
        var badControl = Provider(new SequenceHandler(Json("{\"leaseA\":{\"uid\":\"uidA\"}}")));
        var controlResult = await badControl.ReadSessionAsync(authority, default);

        var badConnection = Provider(new SequenceHandler(
            Control(authority),
            Json("{\"connectionA\":{\"state\":\"disconnected\",\"observedAt\":1,\"extra\":true}}")));
        var connectionResult = await badConnection.ReadSessionAsync(authority, default);

        Assert.False(controlResult.Complete);
        Assert.False(connectionResult.Complete);
        Assert.Empty(connectionResult.Connections);
    }

    [Fact]
    public async Task Stale_or_already_inactive_lease_cannot_be_renewed()
    {
        var authority = Authority();
        var handler = new SequenceHandler(Control(authority, active: false));
        var provider = Provider(handler);
        var session = new PresenceValidatedSession(authority, "subject", "Person", false);

        var renewed = await provider.RenewLeaseAsync(session, "leaseA", Now.AddSeconds(90), default);

        Assert.Null(renewed);
        Assert.Single(handler.Requests);
        Assert.Equal("GET", handler.Requests[0].Method);
    }

    [Fact]
    public async Task Concurrent_renewals_consume_the_old_lease_once_and_keep_a_stable_uid()
    {
        var authority = Authority();
        var handler = new InventoryHandler(ControlJson(authority));
        var provider = Provider(handler);
        var session = new PresenceValidatedSession(authority, "subject", "Person", false);

        var results = await Task.WhenAll(
            provider.RenewLeaseAsync(session, "leaseA", Now.AddSeconds(90), default),
            provider.RenewLeaseAsync(session, "leaseA", Now.AddSeconds(90), default));

        var renewed = Assert.Single(results, result => result is not null)!;
        Assert.Equal(StableUid(authority), renewed.OpaqueUid);
        Assert.Equal(1, handler.ActiveControlCount);
    }

    [Fact]
    public async Task Concurrent_creates_cannot_exceed_the_session_capacity()
    {
        var authority = Authority();
        var handler = new InventoryHandler("{}");
        var provider = Provider(handler, maximumLeases: 1);
        var session = new PresenceValidatedSession(authority, "subject", "Person", false);

        var results = await Task.WhenAll(
            provider.CreateLeaseAsync(session, Now.AddSeconds(90), default),
            provider.CreateLeaseAsync(session, Now.AddSeconds(90), default));

        Assert.Single(results, result => result is not null);
        Assert.Equal(1, handler.ActiveControlCount);
    }

    private static FirebasePresenceProvider Provider(HttpMessageHandler handler, int maximumLeases = 8) => new(
        new FirebasePresenceHttpTransport(new HttpClient(handler)),
        new FirebasePresenceProfile
        {
            Enabled = true,
            ApplicationKey = "nqrb",
            ProjectId = "approved-project",
            DatabaseUrl = "https://presence.example/",
            AllowedDatabaseHost = "presence.example",
            CredentialJson = "{}",
            MaxLeasesPerSession = maximumLeases,
        },
        new TokenIssuer(),
        new AdminTokens(),
        Options.Create(new PresenceOptions()),
        new FixedClock());

    private static PresenceSessionAuthority Authority() => new(
        Guid.Parse("22222222-2222-2222-2222-222222222222"),
        Guid.Parse("33333333-3333-3333-3333-333333333333"),
        Guid.Parse("11111111-1111-1111-1111-111111111111"),
        "nqrb",
        Now.AddMinutes(5),
        "revision");

    private static HttpResponseMessage Json(string value) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(value, Encoding.UTF8, "application/json"),
    };

    private static HttpResponseMessage Control(PresenceSessionAuthority authority, bool active = true) =>
        Json(ControlJson(authority, active));

    private static string ControlJson(PresenceSessionAuthority authority, bool active = true) =>
        JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["leaseA"] = new
            {
                uid = "uidA",
                connectionId = "connectionA",
                applicationId = authority.ApplicationId,
                applicationKey = authority.ApplicationKey,
                sessionId = authority.SessionId,
                membershipId = authority.MembershipId,
                credentialRevision = authority.CredentialRevision,
                expiresAtUtc = Now.AddMinutes(1).ToUnixTimeMilliseconds(),
                active,
                createdAtUtc = Now.ToUnixTimeMilliseconds(),
            },
        });

    private static string StableUid(PresenceSessionAuthority authority) =>
        Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{authority.ApplicationId:N}\n{authority.SessionId:N}"))).ToLowerInvariant();

    private sealed class FixedClock : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class TokenIssuer : IFirebasePresenceTokenIssuer
    {
        public Task<string> IssueAsync(string uid, string leaseId, string connectionId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
            Task.FromResult("custom-token");
    }

    private sealed class AdminTokens : IFirebasePresenceAdminTokenSource
    {
        public Task<string> GetAsync(CancellationToken cancellationToken) => Task.FromResult("admin-token");
    }

    private sealed class SequenceHandler(params HttpResponseMessage[] responses) : HttpMessageHandler
    {
        private readonly Queue<HttpResponseMessage> pending = new(responses);
        public List<(string Method, string Uri, string? Authorization)> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add((request.Method.Method, request.RequestUri!.ToString(), request.Headers.Authorization?.ToString()));
            return Task.FromResult(pending.Dequeue());
        }
    }

    private sealed class InventoryHandler(string initialControls) : HttpMessageHandler
    {
        private readonly object gate = new();
        private string controls = initialControls;
        private long revision = 1;

        public int ActiveControlCount
        {
            get
            {
                lock (gate)
                {
                    using var document = JsonDocument.Parse(controls);
                    return document.RootElement.EnumerateObject().Count();
                }
            }
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.Method == HttpMethod.Get)
            {
                lock (gate)
                {
                    var response = Json(controls);
                    response.Headers.ETag = new EntityTagHeaderValue($"\"{revision}\"");
                    return response;
                }
            }
            if (request.Method == HttpMethod.Put)
            {
                var body = await request.Content!.ReadAsStringAsync(cancellationToken);
                lock (gate)
                {
                    if (request.Headers.IfMatch.SingleOrDefault()?.Tag != $"\"{revision}\"")
                        return new HttpResponseMessage(HttpStatusCode.PreconditionFailed);
                    controls = body;
                    revision++;
                    return Json("null");
                }
            }
            if (request.Method == HttpMethod.Patch) return Json("null");
            return new HttpResponseMessage(HttpStatusCode.MethodNotAllowed);
        }
    }
}
