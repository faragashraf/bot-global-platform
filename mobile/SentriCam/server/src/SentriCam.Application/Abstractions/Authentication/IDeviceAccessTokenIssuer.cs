using SentriCam.Domain.Devices;

namespace SentriCam.Application.Abstractions.Authentication;

public sealed record DeviceAccessToken(string Value, DateTimeOffset ExpiresAtUtc);

public interface IDeviceAccessTokenIssuer
{
    DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        DateTimeOffset issuedAtUtc);

    DeviceAccessToken Issue(
        DeviceId deviceId,
        string installationId,
        Guid registrationId,
        DateTimeOffset issuedAtUtc);
}

public interface IDeviceCredentialValidator
{
    Task<bool> IsCurrentAsync(
        DeviceId deviceId,
        Guid registrationId,
        CancellationToken cancellationToken = default);
}

public sealed record OperatorAccessToken(string Value, DateTimeOffset ExpiresAtUtc);

public interface IOperatorAccessTokenIssuer
{
    OperatorAccessToken Issue(DateTimeOffset issuedAtUtc);
}
