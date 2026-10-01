using SentriCam.Application.Registration;
using SentriCam.Contracts.Commands;
using SentriCam.Contracts.Registration;

namespace SentriCam.Application.Commands;

public sealed class RegisterDeviceCommandHandler(IRegistrationService registrationService)
    : IRegisterDeviceCommandHandler
{
    public Task<RegistrationResult> HandleAsync(
        RegisterDevice command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        return registrationService.RegisterAsync(command.Registration, cancellationToken);
    }

    public Task<RegistrationResult> HandlePairingAsync(
        RegisterDevice command,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(command);
        return registrationService.RegisterFromPairingAsync(command.Registration, cancellationToken);
    }
}
