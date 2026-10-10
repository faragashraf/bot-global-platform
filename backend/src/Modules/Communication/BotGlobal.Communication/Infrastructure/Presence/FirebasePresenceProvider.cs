using System.Net.Http.Headers;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using BotGlobal.Communication.Application.Presence;
using BotGlobal.Contracts.Communication;
using FirebaseAdmin.Auth;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Options;

namespace BotGlobal.Communication.Infrastructure.Presence;

internal sealed class DisabledPresenceProvider : IPresenceProvider
{
    public bool IsEnabled(string applicationKey) => false;
    public Task<PresenceProviderLease?> CreateLeaseAsync(PresenceValidatedSession session, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) => Task.FromResult<PresenceProviderLease?>(null);
    public Task<PresenceProviderLease?> RenewLeaseAsync(PresenceValidatedSession session, string leaseId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) => Task.FromResult<PresenceProviderLease?>(null);
    public Task<PresenceProviderSessionSnapshot> ReadSessionAsync(PresenceSessionAuthority authority, CancellationToken cancellationToken) => Task.FromResult(new PresenceProviderSessionSnapshot(false, false, false, []));
    public Task InvalidateAsync(PresenceSessionAuthority authority, string leaseId, CancellationToken cancellationToken) => Task.CompletedTask;
}

internal sealed class ApplicationScopedPresenceProvider(
    IReadOnlyDictionary<string, FirebasePresenceProvider> providers) : IPresenceProvider
{
    public bool IsEnabled(string applicationKey) => providers.ContainsKey(applicationKey);

    public Task<PresenceProviderLease?> CreateLeaseAsync(PresenceValidatedSession session, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
        Provider(session.Authority.ApplicationKey)?.CreateLeaseAsync(session, expiresAtUtc, cancellationToken) ??
        Task.FromResult<PresenceProviderLease?>(null);

    public Task<PresenceProviderLease?> RenewLeaseAsync(PresenceValidatedSession session, string leaseId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken) =>
        Provider(session.Authority.ApplicationKey)?.RenewLeaseAsync(session, leaseId, expiresAtUtc, cancellationToken) ??
        Task.FromResult<PresenceProviderLease?>(null);

    public Task<PresenceProviderSessionSnapshot> ReadSessionAsync(PresenceSessionAuthority authority, CancellationToken cancellationToken) =>
        Provider(authority.ApplicationKey)?.ReadSessionAsync(authority, cancellationToken) ??
        Task.FromResult(new PresenceProviderSessionSnapshot(false, false, false, []));

    public Task InvalidateAsync(PresenceSessionAuthority authority, string leaseId, CancellationToken cancellationToken) =>
        Provider(authority.ApplicationKey)?.InvalidateAsync(authority, leaseId, cancellationToken) ?? Task.CompletedTask;

    private FirebasePresenceProvider? Provider(string applicationKey) =>
        providers.TryGetValue(applicationKey, out var provider) ? provider : null;
}

internal sealed class FirebasePresenceHttpTransport(HttpClient client)
{
    public HttpClient Client { get; } = client;
}

internal sealed class FirebasePresenceProvider(
    FirebasePresenceHttpTransport transport,
    FirebasePresenceProfile profile,
    IFirebasePresenceTokenIssuer tokenIssuer,
    IFirebasePresenceAdminTokenSource adminTokens,
    IOptions<PresenceOptions> options,
    TimeProvider timeProvider) : IPresenceProvider
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private readonly ConcurrentDictionary<string, SemaphoreSlim> sessionLocks = new(StringComparer.Ordinal);
    private readonly Uri database = new(profile.DatabaseUrl, UriKind.Absolute);
    private readonly HttpClient http = transport.Client;

    public bool IsEnabled(string applicationKey) => profile.Enabled &&
        string.Equals(applicationKey, profile.ApplicationKey, StringComparison.Ordinal);

    public async Task<PresenceProviderLease?> CreateLeaseAsync(
        PresenceValidatedSession session,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken)
    {
        return await ReserveLeaseAsync(session, null, expiresAtUtc, cancellationToken);
    }

    public async Task<PresenceProviderSessionSnapshot> ReadSessionAsync(
        PresenceSessionAuthority authority,
        CancellationToken cancellationToken)
    {
        if (!IsEnabled(authority.ApplicationKey)) return new(false, false, false, []);
        var controls = await ReadControlsAsync(authority, cancellationToken);
        if (controls.Malformed) return new(false, false, controls.Overflow, []);
        var now = timeProvider.GetUtcNow();
        var active = controls.Controls.Where(item => item.Active && item.ExpiresAtUtc > now.ToUnixTimeMilliseconds() &&
            string.Equals(item.CredentialRevision, authority.CredentialRevision, StringComparison.Ordinal)).ToArray();
        if (active.Length == 0) return new(false, false, controls.Overflow, []);
        var connections = new List<PresenceProviderConnection>();
        var complete = !controls.Overflow;
        foreach (var control in active)
        {
            using var document = await GetJsonAsync($"presenceConnections/{control.Uid}/{control.LeaseId}", cancellationToken);
            if (document is null || document.RootElement.ValueKind != JsonValueKind.Object)
            {
                complete = false;
                continue;
            }
            var count = 0;
            foreach (var child in document.RootElement.EnumerateObject())
            {
                if (++count > options.Value.MaxConnectionsPerSession) return new(true, false, true, connections);
                if (!TryConnection(child.Value, out var connection))
                {
                    complete = false;
                    continue;
                }
                connections.Add(connection!);
            }
            if (count == 0) complete = false;
        }
        return new(true, complete, controls.Overflow, connections);
    }

    public async Task<PresenceProviderLease?> RenewLeaseAsync(
        PresenceValidatedSession session,
        string leaseId,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken)
    {
        return await ReserveLeaseAsync(session, leaseId, expiresAtUtc, cancellationToken);
    }

    public async Task InvalidateAsync(
        PresenceSessionAuthority authority,
        string leaseId,
        CancellationToken cancellationToken)
    {
        if (!IsEnabled(authority.ApplicationKey)) return;
        var sessionLock = SessionLock(authority);
        await sessionLock.WaitAsync(cancellationToken);
        try
        {
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var controls = await ReadControlsAsync(authority, cancellationToken, requestEtag: true);
                var control = controls.Controls.SingleOrDefault(item =>
                    string.Equals(item.LeaseId, leaseId, StringComparison.Ordinal) &&
                    string.Equals(item.CredentialRevision, authority.CredentialRevision, StringComparison.Ordinal));
                if (controls.Malformed || controls.Overflow || control is null) return;
                var retained = controls.Controls.Where(item => !string.Equals(item.LeaseId, leaseId, StringComparison.Ordinal))
                    .ToDictionary(item => item.LeaseId, StringComparer.Ordinal);
                if (!await PutControlsAsync(authority, retained, controls.ETag, cancellationToken)) continue;
                await PatchRootAsync(new Dictionary<string, object?>
                {
                    [$"__presenceControls/byCapability/{control.Uid}/{leaseId}"] = null,
                    [$"presenceConnections/{control.Uid}/{leaseId}"] = null
                }, cancellationToken);
                return;
            }
        }
        finally
        {
            sessionLock.Release();
        }
    }

    private async Task<PresenceProviderLease?> ReserveLeaseAsync(
        PresenceValidatedSession session,
        string? replacedLeaseId,
        DateTimeOffset expiresAtUtc,
        CancellationToken cancellationToken)
    {
        if (!IsEnabled(session.Authority.ApplicationKey) || expiresAtUtc <= timeProvider.GetUtcNow()) return null;
        var sessionLock = SessionLock(session.Authority);
        await sessionLock.WaitAsync(cancellationToken);
        try
        {
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var inventory = await ReadControlsAsync(session.Authority, cancellationToken, requestEtag: true);
                if (inventory.Malformed || inventory.Overflow) return null;
                var now = timeProvider.GetUtcNow().ToUnixTimeMilliseconds();
                var active = inventory.Controls.Where(item => item.Active && item.ExpiresAtUtc > now &&
                    string.Equals(item.CredentialRevision, session.Authority.CredentialRevision, StringComparison.Ordinal)).ToList();
                ControlDocument? replaced = null;
                if (replacedLeaseId is not null)
                {
                    replaced = active.SingleOrDefault(item => string.Equals(item.LeaseId, replacedLeaseId, StringComparison.Ordinal));
                    if (replaced is null) return null;
                    active.Remove(replaced);
                }
                if (active.Count >= profile.MaxLeasesPerSession) return null;

                var uid = StableUid(session.Authority);
                var leaseId = OpaqueId(24);
                var connectionId = OpaqueId(18);
                var control = new ControlDocument(
                    uid,
                    connectionId,
                    session.Authority.ApplicationId,
                    session.Authority.ApplicationKey,
                    session.Authority.SessionId,
                    session.Authority.MembershipId,
                    session.Authority.CredentialRevision,
                    expiresAtUtc.ToUnixTimeMilliseconds(),
                    true,
                    now) { LeaseId = leaseId };
                var retained = active.ToDictionary(item => item.LeaseId, StringComparer.Ordinal);
                retained.Add(leaseId, control);
                if (!await PutControlsAsync(session.Authority, retained, inventory.ETag, cancellationToken)) continue;

                var cleanup = inventory.Controls.Where(item => !retained.ContainsKey(item.LeaseId)).ToArray();
                var patch = new Dictionary<string, object?>
                {
                    [$"__presenceControls/byCapability/{uid}/{leaseId}"] = control
                };
                foreach (var stale in cleanup)
                {
                    patch[$"__presenceControls/byCapability/{stale.Uid}/{stale.LeaseId}"] = null;
                    patch[$"presenceConnections/{stale.Uid}/{stale.LeaseId}"] = null;
                }
                try
                {
                    await PatchRootAsync(patch, cancellationToken);
                    var customToken = await tokenIssuer.IssueAsync(uid, leaseId, connectionId, expiresAtUtc, cancellationToken);
                    return new PresenceProviderLease(
                        leaseId,
                        uid,
                        connectionId,
                        customToken,
                        profile.DatabaseUrl,
                        $"presenceConnections/{uid}/{leaseId}/{connectionId}",
                        expiresAtUtc);
                }
                catch
                {
                    await RemoveReservedLeaseAsync(session.Authority, control, cancellationToken);
                    throw;
                }
            }
            return null;
        }
        finally
        {
            sessionLock.Release();
        }
    }

    private async Task RemoveReservedLeaseAsync(
        PresenceSessionAuthority authority,
        ControlDocument reserved,
        CancellationToken cancellationToken)
    {
        try
        {
            for (var attempt = 0; attempt < 3; attempt++)
            {
                var inventory = await ReadControlsAsync(authority, cancellationToken, requestEtag: true);
                var retained = inventory.Controls.Where(item => !string.Equals(item.LeaseId, reserved.LeaseId, StringComparison.Ordinal))
                    .ToDictionary(item => item.LeaseId, StringComparer.Ordinal);
                if (!await PutControlsAsync(authority, retained, inventory.ETag, cancellationToken)) continue;
                await PatchRootAsync(new Dictionary<string, object?>
                {
                    [$"__presenceControls/byCapability/{reserved.Uid}/{reserved.LeaseId}"] = null,
                    [$"presenceConnections/{reserved.Uid}/{reserved.LeaseId}"] = null
                }, cancellationToken);
                return;
            }
        }
        catch when (!cancellationToken.IsCancellationRequested)
        {
            // The provisional lease is not returned to a client. A later bounded
            // inventory mutation prunes the orphaned control.
        }
    }

    private SemaphoreSlim SessionLock(PresenceSessionAuthority authority) =>
        sessionLocks.GetOrAdd(SessionControlRoot(authority), _ => new SemaphoreSlim(1, 1));

    private async Task<ControlInventory> ReadControlsAsync(
        PresenceSessionAuthority authority,
        CancellationToken cancellationToken,
        bool requestEtag = false)
    {
        var response = await GetJsonResponseAsync(SessionControlRoot(authority), cancellationToken, requestEtag);
        using var document = response.Document;
        if (document is null || document.RootElement.ValueKind == JsonValueKind.Null) return new([], false, false, response.ETag);
        if (document.RootElement.ValueKind != JsonValueKind.Object) return new([], true, false, response.ETag);
        var result = new List<ControlDocument>();
        foreach (var lease in document.RootElement.EnumerateObject())
        {
            if (result.Count >= profile.MaxLeasesPerSession) return new(result, false, true, response.ETag);
            try
            {
                var parsed = lease.Value.Deserialize<ControlDocument>(JsonOptions);
                if (parsed is null || parsed.SessionId != authority.SessionId ||
                    parsed.MembershipId != authority.MembershipId || parsed.ApplicationId != authority.ApplicationId ||
                    !string.Equals(parsed.ApplicationKey, authority.ApplicationKey, StringComparison.Ordinal) ||
                    !IsOpaqueSegment(parsed.Uid, 64) || !IsOpaqueSegment(parsed.ConnectionId, 64) ||
                    !IsOpaqueSegment(lease.Name, 80))
                    return new([], true, false, response.ETag);
                result.Add(parsed with { LeaseId = lease.Name });
            }
            catch (JsonException) { return new([], true, false, response.ETag); }
        }
        return new(result, false, false, response.ETag);
    }

    private async Task<JsonDocument?> GetJsonAsync(string relativePath, CancellationToken cancellationToken)
    {
        var response = await GetJsonResponseAsync(relativePath, cancellationToken, requestEtag: false);
        return response.Document;
    }

    private async Task<(JsonDocument? Document, string? ETag)> GetJsonResponseAsync(
        string relativePath,
        CancellationToken cancellationToken,
        bool requestEtag)
    {
        using var request = await RequestAsync(HttpMethod.Get, relativePath, null, cancellationToken);
        if (requestEtag) request.Headers.TryAddWithoutValidation("X-Firebase-ETag", "true");
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        RejectRedirect(response);
        response.EnsureSuccessStatusCode();
        var bytes = await ReadBoundedAsync(response.Content, cancellationToken);
        return (JsonDocument.Parse(bytes), response.Headers.ETag?.Tag);
    }

    private async Task<bool> PutControlsAsync(
        PresenceSessionAuthority authority,
        IReadOnlyDictionary<string, ControlDocument> controls,
        string? etag,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(etag))
            throw new InvalidDataException("Presence provider did not return the required concurrency token.");
        var content = JsonSerializer.SerializeToUtf8Bytes(controls, JsonOptions);
        using var request = await RequestAsync(HttpMethod.Put, SessionControlRoot(authority), content, cancellationToken);
        request.Headers.TryAddWithoutValidation("if-match", etag);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        RejectRedirect(response);
        if (response.StatusCode == System.Net.HttpStatusCode.PreconditionFailed) return false;
        response.EnsureSuccessStatusCode();
        return true;
    }

    private async Task<byte[]> ReadBoundedAsync(HttpContent content, CancellationToken cancellationToken)
    {
        await using var stream = await content.ReadAsStreamAsync(cancellationToken);
        using var buffer = new MemoryStream();
        var chunk = new byte[8192];
        while (true)
        {
            var read = await stream.ReadAsync(chunk.AsMemory(0, chunk.Length), cancellationToken);
            if (read == 0) break;
            if (buffer.Length + read > options.Value.MaxProviderResponseBytes)
                throw new InvalidDataException("Presence provider response exceeded the configured bound.");
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }

    private async Task PatchRootAsync(IReadOnlyDictionary<string, object?> patch, CancellationToken cancellationToken)
    {
        var content = JsonSerializer.SerializeToUtf8Bytes(patch, JsonOptions);
        if (content.Length > options.Value.MaxProviderResponseBytes) throw new InvalidDataException("Presence provider request exceeded the configured bound.");
        using var request = await RequestAsync(HttpMethod.Patch, string.Empty, content, cancellationToken);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        RejectRedirect(response);
        response.EnsureSuccessStatusCode();
    }

    private async Task<HttpRequestMessage> RequestAsync(
        HttpMethod method,
        string relativePath,
        byte[]? json,
        CancellationToken cancellationToken)
    {
        var uri = new Uri(database, relativePath.Trim('/') + ".json");
        if (!string.Equals(uri.Scheme, database.Scheme, StringComparison.Ordinal) ||
            !string.Equals(uri.IdnHost, database.IdnHost, StringComparison.Ordinal) || uri.Port != database.Port)
            throw new InvalidOperationException("Presence provider target escaped the approved origin.");
        var request = new HttpRequestMessage(method, uri);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await adminTokens.GetAsync(cancellationToken));
        if (json is not null)
        {
            request.Content = new ByteArrayContent(json);
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }
        return request;
    }

    private static void RejectRedirect(HttpResponseMessage response)
    {
        if ((int)response.StatusCode is >= 300 and < 400)
            throw new HttpRequestException("Presence provider redirects are not permitted.");
    }

    private static bool TryConnection(JsonElement element, out PresenceProviderConnection? connection)
    {
        connection = null;
        if (element.ValueKind != JsonValueKind.Object || element.EnumerateObject().Count() != 2 ||
            !element.TryGetProperty("state", out var state) || !element.TryGetProperty("observedAt", out var observedAt) ||
            state.ValueKind != JsonValueKind.String || observedAt.ValueKind != JsonValueKind.Number ||
            !observedAt.TryGetInt64(out var milliseconds)) return false;
        var parsed = state.GetString() switch
        {
            "connected" => PresenceEvidenceState.Connected,
            "disconnected" => PresenceEvidenceState.Disconnected,
            _ => PresenceEvidenceState.Unknown
        };
        if (parsed == PresenceEvidenceState.Unknown) return false;
        try { connection = new(parsed, DateTimeOffset.FromUnixTimeMilliseconds(milliseconds)); return true; }
        catch (ArgumentOutOfRangeException) { return false; }
    }

    private static string SessionControlRoot(PresenceSessionAuthority authority) =>
        $"__presenceControls/bySession/{authority.ApplicationId:N}/{authority.SessionId:N}";
    private static string SessionControlPath(PresenceSessionAuthority authority, string leaseId) =>
        $"{SessionControlRoot(authority)}/{leaseId}";
    private static string OpaqueId(int bytes) =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(bytes)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private static string StableUid(PresenceSessionAuthority authority) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
            $"{authority.ApplicationId:N}\n{authority.SessionId:N}"))).ToLowerInvariant();
    private static bool IsOpaqueSegment(string value, int maxLength) =>
        !string.IsNullOrWhiteSpace(value) && value.Length <= maxLength &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_');

    private sealed record ControlDocument(
        string Uid,
        string ConnectionId,
        Guid ApplicationId,
        string ApplicationKey,
        Guid SessionId,
        Guid MembershipId,
        string CredentialRevision,
        long ExpiresAtUtc,
        bool Active,
        long CreatedAtUtc)
    {
        public string LeaseId { get; init; } = string.Empty;
    }

    private sealed record ControlInventory(
        IReadOnlyList<ControlDocument> Controls,
        bool Malformed,
        bool Overflow,
        string? ETag);
}

internal sealed class FirebaseAdminPresenceTokenIssuer(FirebaseAuth auth) : IFirebasePresenceTokenIssuer
{
    public async Task<string> IssueAsync(string uid, string leaseId, string connectionId, DateTimeOffset expiresAtUtc, CancellationToken cancellationToken)
    {
        var claims = new Dictionary<string, object>
        {
            ["lease"] = leaseId,
            ["connection"] = connectionId,
            ["leaseExp"] = expiresAtUtc.ToUnixTimeMilliseconds()
        };
        return await auth.CreateCustomTokenAsync(uid, claims).WaitAsync(cancellationToken);
    }
}

internal sealed class GooglePresenceAdminTokenSource(ITokenAccess credential) : IFirebasePresenceAdminTokenSource
{
    public Task<string> GetAsync(CancellationToken cancellationToken) =>
        credential.GetAccessTokenForRequestAsync(cancellationToken: cancellationToken);
}
