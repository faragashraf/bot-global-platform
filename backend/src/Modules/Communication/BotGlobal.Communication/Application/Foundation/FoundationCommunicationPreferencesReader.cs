using BotGlobal.Communication.Application.Abstractions;
using BotGlobal.Communication.Contracts.Calls;

namespace BotGlobal.Communication.Application.Foundation;

internal sealed class FoundationCommunicationPreferencesReader
    : ICommunicationPreferencesReader
{
    public Task<CommunicationPreferences> GetAsync(
        string userId,
        CancellationToken cancellationToken = default)
        => Task.FromResult(
            new CommunicationPreferences(
                AllowVoiceCalls: false,
                AllowVideoCalls: false));
}
