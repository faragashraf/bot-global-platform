using BotGlobal.Contracts.Communication;
using BotGlobal.Contracts.Notifications;

namespace BotGlobal.Communication.Application.Chat;

public sealed class ChatPolicyRegistry(
    IEnumerable<IChatParticipantDirectory> directories,
    IEnumerable<IChatAccessPolicy> policies,
    IPlatformClientDescriptorReader applications)
{
    private readonly IChatParticipantDirectory[] _directories = directories.ToArray();
    private readonly IChatAccessPolicy[] _policies = policies.ToArray();

    public (IChatParticipantDirectory Directory, IChatAccessPolicy Policy)? Resolve(string applicationKey)
    {
        var matchingDirectories = _directories.Where(x => string.Equals(x.ApplicationKey, applicationKey, StringComparison.Ordinal)).ToArray();
        var matchingPolicies = _policies.Where(x => string.Equals(x.ApplicationKey, applicationKey, StringComparison.Ordinal)).ToArray();
        return matchingDirectories.Length == 1 && matchingPolicies.Length == 1
            ? (matchingDirectories[0], matchingPolicies[0])
            : null;
    }

    public async Task<string?> ResolveByApplicationIdAsync(Guid applicationId, CancellationToken token)
    {
        var descriptor = await applications.FindAsync(applicationId, token);
        return descriptor is { IsActive: true } ? descriptor.ClientKey : null;
    }
}
