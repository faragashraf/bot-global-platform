using SentriCam.Contracts.Registration;

namespace SentriCam.Application.Registration;

public interface IRegistrationService
{
    Task<RegistrationResult> RegisterAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default);

    Task<RegistrationResult> RegisterFromPairingAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default);
}
