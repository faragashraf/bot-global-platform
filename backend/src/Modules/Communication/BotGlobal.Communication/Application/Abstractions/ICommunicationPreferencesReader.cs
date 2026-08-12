using BotGlobal.Communication.Contracts.Calls;

namespace BotGlobal.Communication.Application.Abstractions;

public interface ICommunicationPreferencesReader
{
    Task<CommunicationPreferences> GetAsync(
        string userId,
        CancellationToken cancellationToken = default);
}
