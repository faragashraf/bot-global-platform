using BotGlobal.PlatformClients.Authentication;
using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.PlatformClients.Authorization;

public sealed class PlatformClientCapabilityHandler
    : AuthorizationHandler<PlatformClientCapabilityRequirement>
{
    protected override Task HandleRequirementAsync(
        AuthorizationHandlerContext context,
        PlatformClientCapabilityRequirement requirement)
    {
        if (context.User.Identity?.IsAuthenticated == true
            && string.Equals(
                context.User.Identity.AuthenticationType,
                PlatformClientAuthenticationDefaults.Scheme,
                StringComparison.Ordinal)
            && context.User.HasClaim(
                PlatformClientAuthenticationDefaults.CapabilityClaim,
                requirement.Capability))
        {
            context.Succeed(requirement);
        }

        return Task.CompletedTask;
    }
}
