using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;
using BotGlobal.Contracts.Calling;
using BotGlobal.Contracts.Mobile;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using System.Security.Claims;
using System.Text.Encodings.Web;

namespace BotGlobal.Calling.Realtime;

public static class NqrbGuestCallAuthenticationDefaults
{
    public const string Scheme = "BotGlobal.NqrbGuestCall";
    public const string CookieName = "nqrb_guest_call";
    public const string InviteIdClaim = "botglobal:nqrb_guest_call_invite_id";
    public const string CallIdClaim = "botglobal:nqrb_guest_call_id";
}

public sealed class NqrbGuestCallInviteOptions
{
    public const string SectionName = "Calling:NqrbGuestInvites";
    public int InviteLifetimeMinutes { get; set; } = 15;
    public int RingLifetimeSeconds { get; set; } = 45;
    public string? PublicPageUrl { get; set; }
}

public sealed record NqrbGuestCallInviteCreated(Guid InviteId, string Capability, DateTimeOffset ExpiresAtUtc);
public sealed record NqrbGuestCallInvitePreview(string HostDisplayName, DateTimeOffset ExpiresAtUtc);
public sealed record NqrbGuestCallAccepted(
    Guid InviteId,
    Guid CallId,
    string GuestAccessToken,
    Guid GuestMembershipId,
    Guid HostMembershipId,
    string HostSubjectId,
    string HostDisplayName,
    string GuestDisplayName,
    DateTimeOffset ExpiresAtUtc,
    DateTimeOffset InviteExpiresAtUtc,
    DateTimeOffset GuestAccessExpiresAtUtc,
    bool IsNew);

public enum NqrbGuestCallInviteStatus
{
    Created = 1,
    Accepted = 2,
    Invalid = 3,
    Expired = 4,
    Revoked = 5,
    AlreadyUsed = 6,
    HostUnavailable = 7,
    MicrophoneConsentRequired = 8,
    Rejected = 9,
    Cancelled = 10,
}

public sealed record NqrbGuestCallInviteResult<T>(NqrbGuestCallInviteStatus Status, T? Value = default);

public sealed class NqrbGuestCallInviteService(
    CallSessionRegistry sessions,
    IOptions<NqrbGuestCallInviteOptions> options,
    TimeProvider timeProvider)
{
    private static readonly TimeSpan MinimumInviteLifetime = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan MaximumInviteLifetime = TimeSpan.FromMinutes(60);
    private static readonly TimeSpan MinimumRingLifetime = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan MaximumRingLifetime = TimeSpan.FromMinutes(2);
    private readonly ConcurrentDictionary<Guid, GuestInvite> invites = new();
    private readonly ConcurrentDictionary<string, Guid> inviteHashes = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Guid> guestTokenHashes = new(StringComparer.Ordinal);
    private readonly object gate = new();

    public NqrbGuestCallInviteCreated CreateHostInvite(ApplicationIdentityDescriptor host)
    {
        if (!string.Equals(host.ApplicationKey, BotGlobalApplications.Nqrb, StringComparison.Ordinal) || host.IsGuest)
            throw new InvalidOperationException("nqrb_host_account_required");

        Cleanup(timeProvider.GetUtcNow());
        var capability = NewCapability();
        var invite = new GuestInvite(
            Guid.NewGuid(),
            Hash(capability),
            host.MembershipId,
            host.SubjectId,
            NormalizeDisplayName(host.DisplayName, "NQRB host"),
            timeProvider.GetUtcNow().Add(InviteLifetime()));

        lock (gate)
        {
            invites[invite.InviteId] = invite;
            inviteHashes[invite.CapabilityHash] = invite.InviteId;
        }

        return new NqrbGuestCallInviteCreated(invite.InviteId, capability, invite.ExpiresAtUtc);
    }

    public NqrbGuestCallInviteStatus RevokeHostInvite(Guid hostMembershipId, Guid inviteId)
    {
        lock (gate)
        {
            if (!invites.TryGetValue(inviteId, out var invite) || invite.HostMembershipId != hostMembershipId)
                return NqrbGuestCallInviteStatus.Invalid;
            if (invite.AcceptedAtUtc is not null && invite.CompletedAtUtc is null)
                return NqrbGuestCallInviteStatus.AlreadyUsed;
            invite.RevokedAtUtc ??= timeProvider.GetUtcNow();
            return NqrbGuestCallInviteStatus.Revoked;
        }
    }

    public void RevokeHostInvites(Guid hostMembershipId)
    {
        lock (gate)
        {
            foreach (var invite in invites.Values.Where(item => item.HostMembershipId == hostMembershipId))
                invite.RevokedAtUtc ??= timeProvider.GetUtcNow();
        }
    }

    public NqrbGuestCallInviteResult<NqrbGuestCallInvitePreview> Preview(string capability, string? clientRequestId = null)
    {
        if (!IsCapabilityShapeValid(capability)) return new(NqrbGuestCallInviteStatus.Invalid);
        Cleanup(timeProvider.GetUtcNow());
        lock (gate)
        {
            var invite = FindByCapabilityLocked(capability);
            if (invite?.CallId is Guid callId &&
                !string.IsNullOrWhiteSpace(invite.ClientRequestHash) &&
                string.Equals(invite.ClientRequestHash, NormalizeRetryKey(clientRequestId), StringComparison.Ordinal))
            {
                var terminal = sessions.GuestCallStatus(callId, invite.InviteId);
                if (terminal == CallSessionRegistry.CallStatus.Rejected)
                    return new(NqrbGuestCallInviteStatus.Rejected);
                if (terminal == CallSessionRegistry.CallStatus.Expired)
                    return new(NqrbGuestCallInviteStatus.Expired);
                if (terminal == CallSessionRegistry.CallStatus.Cancelled)
                    return new(NqrbGuestCallInviteStatus.Cancelled);
            }
            var status = StatusFor(invite, timeProvider.GetUtcNow(), requireUnused: false);
            return status == NqrbGuestCallInviteStatus.Created
                ? new(status, new NqrbGuestCallInvitePreview(invite!.HostDisplayName, invite.ExpiresAtUtc))
                : new(status);
        }
    }

    public NqrbGuestCallInviteResult<NqrbGuestCallAccepted> Accept(
        string capability,
        string guestDisplayName,
        bool microphoneConsent,
        string? clientRequestId)
    {
        if (!microphoneConsent) return new(NqrbGuestCallInviteStatus.MicrophoneConsentRequired);
        if (!IsCapabilityShapeValid(capability)) return new(NqrbGuestCallInviteStatus.Invalid);
        Cleanup(timeProvider.GetUtcNow());
        lock (gate)
        {
            var now = timeProvider.GetUtcNow();
            var invite = FindByCapabilityLocked(capability);
            var retryKey = NormalizeRetryKey(clientRequestId);
            if (invite?.AcceptedAtUtc is not null)
            {
                return invite.RevokedAtUtc is null &&
                    invite.CompletedAtUtc is null &&
                    invite.GuestAccessExpiresAtUtc > now &&
                    !string.IsNullOrWhiteSpace(retryKey) &&
                    string.Equals(invite.ClientRequestHash, retryKey, StringComparison.Ordinal) &&
                    invite.GuestMembershipId is Guid guestId &&
                    invite.CallId is Guid callId &&
                    sessions.IsLiveCall(callId) &&
                    !string.IsNullOrWhiteSpace(invite.GuestAccessToken)
                        ? new(NqrbGuestCallInviteStatus.Accepted, new NqrbGuestCallAccepted(
                            invite.InviteId,
                            callId,
                            invite.GuestAccessToken,
                            guestId,
                            invite.HostMembershipId,
                            invite.HostSubjectId,
                            invite.HostDisplayName,
                            invite.GuestDisplayName,
                            invite.CallExpiresAtUtc ?? invite.ExpiresAtUtc,
                            invite.ExpiresAtUtc,
                            invite.GuestAccessExpiresAtUtc.Value,
                            false))
                        : new(NqrbGuestCallInviteStatus.AlreadyUsed);
            }

            var status = StatusFor(invite, now, requireUnused: false);
            if (status != NqrbGuestCallInviteStatus.Created) return new(status);

            var guestMembershipId = Guid.NewGuid();
            var guestToken = NewCapability();
            var normalizedGuestName = NormalizeDisplayName(guestDisplayName, "Guest");
            var guest = new CallingParticipantDescriptor(
                guestMembershipId,
                BotGlobalApplications.Nqrb,
                $"guest-call:{invite!.InviteId:N}",
                normalizedGuestName,
                true);
            var host = new CallingParticipantDescriptor(
                invite.HostMembershipId,
                BotGlobalApplications.Nqrb,
                invite.HostSubjectId,
                invite.HostDisplayName,
                true);

            CallSessionRegistry.Started started;
            try
            {
                started = sessions.StartGuestInvite(host, guest, invite.InviteId, now, RingLifetime());
            }
            catch (InvalidOperationException error) when (error.Message == "call_peer_busy")
            {
                return new(NqrbGuestCallInviteStatus.HostUnavailable);
            }

            invite.AcceptedAtUtc = now;
            invite.GuestMembershipId = guestMembershipId;
            invite.CallId = started.Session.CallId;
            invite.CallExpiresAtUtc = started.Session.ExpiresAtUtc;
            invite.GuestAccessExpiresAtUtc = now.AddHours(2);
            invite.GuestAccessToken = guestToken;
            invite.GuestTokenHash = Hash(guestToken);
            invite.GuestDisplayName = normalizedGuestName;
            invite.ClientRequestHash = retryKey;
            guestTokenHashes[invite.GuestTokenHash] = invite.InviteId;

            return new(NqrbGuestCallInviteStatus.Accepted, new NqrbGuestCallAccepted(
                invite.InviteId,
                started.Session.CallId,
                guestToken,
                guestMembershipId,
                invite.HostMembershipId,
                invite.HostSubjectId,
                invite.HostDisplayName,
                normalizedGuestName,
                started.Session.ExpiresAtUtc,
                invite.ExpiresAtUtc,
                invite.GuestAccessExpiresAtUtc.Value,
                true));
        }
    }

    public AuthenticatedGuestCall? AuthenticateGuestToken(string accessToken)
    {
        if (!IsCapabilityShapeValid(accessToken)) return null;
        Cleanup(timeProvider.GetUtcNow());
        var tokenHash = Hash(accessToken);
        lock (gate)
        {
            if (!guestTokenHashes.TryGetValue(tokenHash, out var inviteId) ||
                !invites.TryGetValue(inviteId, out var invite) ||
                invite.GuestMembershipId is null ||
                invite.CallId is null ||
                !string.Equals(invite.GuestTokenHash, tokenHash, StringComparison.Ordinal))
                return null;

            if (invite.RevokedAtUtc is not null || invite.CompletedAtUtc is not null ||
                invite.GuestAccessExpiresAtUtc <= timeProvider.GetUtcNow() ||
                !sessions.IsLiveCall(invite.CallId.Value))
                return null;

            return new AuthenticatedGuestCall(
                new ApplicationIdentityDescriptor(
                    invite.GuestMembershipId.Value,
                    null,
                    $"guest-call:{invite.InviteId:N}",
                    BotGlobalApplications.Nqrb,
                    invite.GuestDisplayName,
                    true),
                invite.InviteId,
                invite.CallId.Value);
        }
    }

    public bool IsGuestAuthorized(Guid inviteId, Guid guestMembershipId, Guid callId)
    {
        Cleanup(timeProvider.GetUtcNow());
        lock (gate)
        {
            return invites.TryGetValue(inviteId, out var invite) &&
                invite.RevokedAtUtc is null &&
                invite.CompletedAtUtc is null &&
                invite.GuestAccessExpiresAtUtc > timeProvider.GetUtcNow() &&
                invite.GuestMembershipId == guestMembershipId &&
                invite.CallId == callId &&
                sessions.IsLiveCall(callId);
        }
    }

    public void Complete(Guid? inviteId)
    {
        if (inviteId is null) return;
        lock (gate)
        {
            if (!invites.TryGetValue(inviteId.Value, out var invite)) return;
            invite.CompletedAtUtc ??= timeProvider.GetUtcNow();
        }
    }

    private GuestInvite? FindByCapabilityLocked(string capability) =>
        inviteHashes.TryGetValue(Hash(capability), out var inviteId) &&
        invites.TryGetValue(inviteId, out var invite)
            ? invite
            : null;

    private static NqrbGuestCallInviteStatus StatusFor(GuestInvite? invite, DateTimeOffset now, bool requireUnused)
    {
        if (invite is null) return NqrbGuestCallInviteStatus.Invalid;
        if (invite.RevokedAtUtc is not null) return NqrbGuestCallInviteStatus.Revoked;
        if (invite.ExpiresAtUtc <= now) return NqrbGuestCallInviteStatus.Expired;
        if (invite.CompletedAtUtc is not null || requireUnused && invite.AcceptedAtUtc is not null)
            return NqrbGuestCallInviteStatus.AlreadyUsed;
        return NqrbGuestCallInviteStatus.Created;
    }

    private void Cleanup(DateTimeOffset now)
    {
        foreach (var invite in invites.Values.Where(item =>
                     item.AcceptedAtUtc is null && item.ExpiresAtUtc.AddMinutes(5) <= now ||
                     item.AcceptedAtUtc is not null && item.GuestAccessExpiresAtUtc?.AddMinutes(5) <= now ||
                     item.CompletedAtUtc is DateTimeOffset completed && completed.AddMinutes(5) <= now ||
                     item.RevokedAtUtc is DateTimeOffset revoked && revoked.AddMinutes(5) <= now).ToArray())
        {
            lock (gate)
            {
                if (!invites.TryRemove(invite.InviteId, out var removed)) continue;
                inviteHashes.TryRemove(removed.CapabilityHash, out _);
                if (!string.IsNullOrWhiteSpace(removed.GuestTokenHash))
                    guestTokenHashes.TryRemove(removed.GuestTokenHash, out _);
            }
        }
    }

    private TimeSpan InviteLifetime() =>
        TimeSpan.FromMinutes(options.Value.InviteLifetimeMinutes) switch
        {
            var value when value < MinimumInviteLifetime => MinimumInviteLifetime,
            var value when value > MaximumInviteLifetime => MaximumInviteLifetime,
            var value => value
        };

    private TimeSpan RingLifetime() =>
        TimeSpan.FromSeconds(options.Value.RingLifetimeSeconds) switch
        {
            var value when value < MinimumRingLifetime => MinimumRingLifetime,
            var value when value > MaximumRingLifetime => MaximumRingLifetime,
            var value => value
        };

    private static string NormalizeDisplayName(string value, string fallback)
    {
        var normalized = value.Trim();
        return normalized.Length switch
        {
            0 => fallback,
            > 80 => normalized[..80],
            _ => normalized
        };
    }

    private static string? NormalizeRetryKey(string? value)
    {
        var normalized = value?.Trim();
        if (string.IsNullOrWhiteSpace(normalized)) return null;
        return normalized.Length > 128 ? Hash(normalized) : Hash(normalized);
    }

    private static string NewCapability()
    {
        Span<byte> bytes = stackalloc byte[32];
        RandomNumberGenerator.Fill(bytes);
        return Base64Url(bytes);
    }

    private static bool IsCapabilityShapeValid(string? value) =>
        value is { Length: 43 } && value.All(character =>
            character is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_');

    private static string Hash(string value) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value.Trim()))).ToLowerInvariant();

    private static string Base64Url(ReadOnlySpan<byte> bytes) =>
        Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

    private sealed class GuestInvite(
        Guid inviteId,
        string capabilityHash,
        Guid hostMembershipId,
        string hostSubjectId,
        string hostDisplayName,
        DateTimeOffset expiresAtUtc)
    {
        public Guid InviteId { get; } = inviteId;
        public string CapabilityHash { get; } = capabilityHash;
        public Guid HostMembershipId { get; } = hostMembershipId;
        public string HostSubjectId { get; } = hostSubjectId;
        public string HostDisplayName { get; } = hostDisplayName;
        public DateTimeOffset ExpiresAtUtc { get; } = expiresAtUtc;
        public DateTimeOffset? AcceptedAtUtc { get; set; }
        public DateTimeOffset? RevokedAtUtc { get; set; }
        public DateTimeOffset? CompletedAtUtc { get; set; }
        public Guid? GuestMembershipId { get; set; }
        public Guid? CallId { get; set; }
        public DateTimeOffset? CallExpiresAtUtc { get; set; }
        public DateTimeOffset? GuestAccessExpiresAtUtc { get; set; }
        public string? GuestAccessToken { get; set; }
        public string? GuestTokenHash { get; set; }
        public string? ClientRequestHash { get; set; }
        public string GuestDisplayName { get; set; } = "Guest";
    }
}

public sealed record AuthenticatedGuestCall(ApplicationIdentityDescriptor Identity, Guid InviteId, Guid CallId);

public sealed class NqrbGuestCallAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    NqrbGuestCallInviteService invites)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var token = ReadToken();
        if (string.IsNullOrWhiteSpace(token)) return Task.FromResult(AuthenticateResult.NoResult());

        var authenticated = invites.AuthenticateGuestToken(token);
        if (authenticated is null) return Task.FromResult(AuthenticateResult.Fail("Invalid guest call capability."));

        var descriptor = authenticated.Identity;
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, descriptor.SubjectId),
            new(ClaimTypes.Name, descriptor.DisplayName),
            new(ApplicationIdentityDefaults.MembershipIdClaim, descriptor.MembershipId.ToString()),
            new(ApplicationIdentityDefaults.ApplicationKeyClaim, descriptor.ApplicationKey),
            new(ApplicationIdentityDefaults.GuestClaim, "true"),
            new(NqrbGuestCallAuthenticationDefaults.InviteIdClaim, authenticated.InviteId.ToString()),
            new(NqrbGuestCallAuthenticationDefaults.CallIdClaim, authenticated.CallId.ToString()),
        };

        var identity = new ClaimsIdentity(claims, NqrbGuestCallAuthenticationDefaults.Scheme);
        var principal = new ClaimsPrincipal(identity);
        return Task.FromResult(AuthenticateResult.Success(
            new AuthenticationTicket(principal, NqrbGuestCallAuthenticationDefaults.Scheme)));
    }

    private string? ReadToken() =>
        Request.Path.StartsWithSegments("/hubs/calling") &&
        Request.Cookies.TryGetValue(NqrbGuestCallAuthenticationDefaults.CookieName, out var token)
            ? token
            : null;
}
