using FluentValidation;
using SentriCam.Application.Abstractions.Authentication;
using SentriCam.Application.Abstractions.Persistence;
using SentriCam.Contracts.Registration;
using SentriCam.Domain.Devices;

namespace SentriCam.Application.Registration;

public sealed class RegistrationService(
    IDeviceRepository deviceRepository,
    IDeviceRegistrationRepository registrationRepository,
    IUnitOfWork unitOfWork,
    IDeviceAccessTokenIssuer tokenIssuer,
    IValidator<RegistrationRequest> validator,
    TimeProvider timeProvider) : IRegistrationService
{
    public async Task<RegistrationResult> RegisterAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default) =>
        await RegisterCoreAsync(request, allowPairingReactivation: false, cancellationToken);

    public async Task<RegistrationResult> RegisterFromPairingAsync(
        RegistrationRequest request,
        CancellationToken cancellationToken = default) =>
        await RegisterCoreAsync(request, allowPairingReactivation: true, cancellationToken);

    private async Task<RegistrationResult> RegisterCoreAsync(
        RegistrationRequest request,
        bool allowPairingReactivation,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        await validator.ValidateAndThrowAsync(request, cancellationToken);

        var now = timeProvider.GetUtcNow();
        var identity = DeviceIdentity.Create(
            request.Identity.InstallationId,
            request.Identity.Manufacturer,
            request.Identity.Model,
            request.Identity.Platform,
            request.Identity.OperatingSystemVersion,
            request.Identity.AppVersion);
        var capabilities = (request.Capabilities ?? [])
            .Select(capability => new DeviceCapabilityDefinition(
                capability.Name,
                capability.Version,
                capability.IsEnabled))
            .ToArray();

        var device = await deviceRepository.GetByInstallationIdAsync(
            identity.InstallationId,
            cancellationToken);
        var isNewDevice = device is null;

        if (device is null)
        {
            device = Device.Register(request.DisplayName, identity, capabilities, now);
            deviceRepository.Add(device);
        }
        else
        {
            if (!device.IsEnabled && !allowPairingReactivation)
            {
                throw new SentriCam.Application.Common.ResourceConflictException(
                    "This device was removed. Scan a new pairing QR to connect it again.");
            }
            if (!device.IsEnabled)
            {
                device.ReactivatePairing(now);
            }
            device.RefreshRegistration(request.DisplayName, identity, capabilities, now);
        }

        var registration = DeviceRegistration.Create(
            device.Id,
            isNewDevice ? DeviceRegistrationKind.Initial : DeviceRegistrationKind.Renewal,
            identity.AppVersion,
            now);
        registrationRepository.Add(registration);

        var token = tokenIssuer.Issue(device.Id, identity.InstallationId, registration.Id, now);
        await unitOfWork.SaveChangesAsync(cancellationToken);

        return new RegistrationResult(
            device.Id.Value,
            isNewDevice,
            token.Value,
            token.ExpiresAtUtc,
            now,
            RefreshToken: null,
            RefreshTokenExpirationUtc: null,
            ServerUtcNow: now,
            InstallationId: identity.InstallationId,
            RegistrationState: RegistrationStates.Registered,
            RegistrationSchemaVersion: RegistrationSchema.CurrentVersion,
            ServerUtcOffsetMinutes: (int)TimeZoneInfo.Local.GetUtcOffset(now.UtcDateTime).TotalMinutes,
            ServerTimeZoneId: TimeZoneInfo.Local.Id);
    }
}
