using BotGlobal.PlatformClients.Domain;
using Microsoft.AspNetCore.Authorization;

namespace BotGlobal.PlatformClients.Authorization;

public sealed class PlatformClientCapabilityRequirement
    : IAuthorizationRequirement
{
    public PlatformClientCapabilityRequirement(string capability)
    {
        Capability =
            PlatformClientCapability.NormalizeCapability(capability);
    }

    public string Capability { get; }
}
