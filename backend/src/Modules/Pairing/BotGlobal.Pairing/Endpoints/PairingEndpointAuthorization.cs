using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.Pairing.Endpoints;

public sealed record PairingMachineAuthorizationOptions(
    string PlatformClientIdClaimType,
    Func<string, AuthorizationPolicy> CapabilityPolicyFactory);

public static class PairingCapabilities
{
    public const string Create = "pairing:create";
    public const string Status = "pairing:status";
}
