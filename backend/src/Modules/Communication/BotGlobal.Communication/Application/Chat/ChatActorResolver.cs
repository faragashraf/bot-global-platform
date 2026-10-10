using System.Security.Claims;
using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Contracts.Notifications;
using BotGlobal.Communication.Domain.Chat;

namespace BotGlobal.Communication.Application.Chat;

internal sealed class ChatActorResolver(
    IPlatformClientApplicationResolver applications,
    IPlatformClientDescriptorReader applicationReader) : IChatActorResolver
{
    public async Task<ChatActor?> ResolveAsync(ClaimsPrincipal principal, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(principal);
        var session = principal.Identities.Where(x => x.IsAuthenticated &&
            string.Equals(x.AuthenticationType, ApplicationIdentityDefaults.Scheme, StringComparison.Ordinal)).ToArray();
        var device = principal.Identities.Where(x => x.IsAuthenticated &&
            string.Equals(x.AuthenticationType, MobileDeviceAuthenticationDefaults.Scheme, StringComparison.Ordinal)).ToArray();
        if (session.Length + device.Length != 1) return null;

        if (session.Length == 1)
        {
            var identity = session[0];
            var key = identity.FindFirst(ApplicationIdentityDefaults.ApplicationKeyClaim)?.Value;
            var subject = identity.FindFirst(ClaimTypes.NameIdentifier)?.Value;
            var membership = identity.FindFirst(ApplicationIdentityDefaults.MembershipIdClaim)?.Value;
            var guest = identity.FindFirst(ApplicationIdentityDefaults.GuestClaim)?.Value;
            if (string.IsNullOrWhiteSpace(key) || string.IsNullOrWhiteSpace(subject) ||
                !Guid.TryParse(membership, out _) || string.Equals(guest, "true", StringComparison.OrdinalIgnoreCase)) return null;
            var application = await applications.FindByClientKeyAsync(key, cancellationToken);
            if (application is not { IsActive: true } || !string.Equals(application.ClientKey, key, StringComparison.Ordinal)) return null;
            return new ChatActor(new ChatApplication(application.PlatformClientId, application.ClientKey),
                ChatConversation.NormalizeSubject(subject), ChatActorMechanism.ApplicationSession);
        }

        var deviceIdentity = device[0];
        if (!Guid.TryParse(deviceIdentity.FindFirst(MobileDeviceAuthenticationDefaults.PlatformClientIdClaim)?.Value, out var applicationId) ||
            !Guid.TryParse(deviceIdentity.FindFirst(MobileDeviceAuthenticationDefaults.DeviceIdClaim)?.Value, out var deviceId)) return null;
        var deviceSubject = deviceIdentity.FindFirst(MobileDeviceAuthenticationDefaults.ExternalSubjectIdClaim)?.Value;
        if (string.IsNullOrWhiteSpace(deviceSubject)) return null;
        var descriptor = await applicationReader.FindAsync(applicationId, cancellationToken);
        if (descriptor is not { IsActive: true }) return null;
        return new ChatActor(new ChatApplication(descriptor.PlatformClientId, descriptor.ClientKey),
            ChatConversation.NormalizeSubject(deviceSubject), ChatActorMechanism.PairedDevice, deviceId);
    }
}
