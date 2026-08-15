namespace BotGlobal.Pairing.Application;

public enum UnpairMobileDeviceOutcome
{
    Unpaired,
    InvalidCredential
}

public interface IMobileDeviceLifecycleService
{
    Task<UnpairMobileDeviceOutcome> UnpairAsync(
        string credential,
        CancellationToken cancellationToken);
}
