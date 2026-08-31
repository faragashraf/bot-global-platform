using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Registration;

namespace SentriCam.Application.Commands;

public interface IRegisterDeviceCommandHandler
{
    Task<RegistrationResult> HandleAsync(
        RegisterDevice command,
        CancellationToken cancellationToken = default);

    Task<RegistrationResult> HandlePairingAsync(
        RegisterDevice command,
        CancellationToken cancellationToken = default) => HandleAsync(command, cancellationToken);
}

public interface IPingCommandHandler
{
    Task<PingResult> HandleAsync(
        Ping command,
        CancellationToken cancellationToken = default);
}

public interface IGetStatusCommandHandler
{
    Task<DeviceStatusResult> HandleAsync(
        GetStatus command,
        CancellationToken cancellationToken = default);
}

public interface IDeviceControlCommandHandler
{
    Task<CommandSubmissionResult> HandleAsync(
        IDeviceControlCommand command,
        CancellationToken cancellationToken = default);
}
