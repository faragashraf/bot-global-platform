using BotGlobal.PlatformClients.Authentication;
using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.PlatformClients.Authorization;

public static class PlatformClientPolicies
{
    public static AuthorizationPolicy AuthenticatedClient()
        => new AuthorizationPolicyBuilder(
                PlatformClientAuthenticationDefaults.Scheme)
            .RequireAuthenticatedUser()
            .Build();

    public static AuthorizationPolicy Capability(string capability)
        => new AuthorizationPolicyBuilder(
                PlatformClientAuthenticationDefaults.Scheme)
            .RequireAuthenticatedUser()
            .AddRequirements(
                new PlatformClientCapabilityRequirement(capability))
            .Build();
}
