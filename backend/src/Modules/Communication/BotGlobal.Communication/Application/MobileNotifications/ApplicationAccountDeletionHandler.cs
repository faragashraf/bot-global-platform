using BotGlobal.Contracts.Mobile;

namespace BotGlobal.Communication.Application.MobileNotifications;

internal sealed class CommunicationAccountDeletionHandler(
    IMobileNotificationConnectionRegistry connections) : IApplicationAccountDeletionHandler
{
    public string StepName => "communication";
    public int Order => 75;
    public bool RevokesAccess => true;

    public Task DeleteAsync(ApplicationAccountDeletionScope scope, CancellationToken cancellationToken)
    {
        connections.Forget(scope.MobileDeviceIds);
        return Task.CompletedTask;
    }
}
