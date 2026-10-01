namespace SentriCam.Contracts.Registration;

public sealed record DeviceIdentityContract(
    string InstallationId,
    string Manufacturer,
    string Model,
    string Platform,
    string OperatingSystemVersion,
    string AppVersion,
    string? AppBuild = null,
    int? ApiLevel = null);

public sealed record DeviceCapabilityContract(
    string Name,
    string Version,
    bool IsEnabled = true);

public sealed record RegistrationRequest(
    string DisplayName,
    DeviceIdentityContract Identity,
    IReadOnlyCollection<DeviceCapabilityContract>? Capabilities = null,
    int ClientRegistrationSchemaVersion = RegistrationSchema.CurrentVersion,
    DateTimeOffset? ClientUtcNow = null);

public sealed record RegistrationResult(
    Guid DeviceId,
    bool IsNewDevice,
    string AccessToken,
    DateTimeOffset AccessTokenExpiresAtUtc,
    DateTimeOffset RegisteredAtUtc,
    string? RefreshToken = null,
    DateTimeOffset? RefreshTokenExpirationUtc = null,
    DateTimeOffset ServerUtcNow = default,
    string? InstallationId = null,
    string RegistrationState = RegistrationStates.Registered,
    int RegistrationSchemaVersion = RegistrationSchema.CurrentVersion,
    int? ServerUtcOffsetMinutes = null,
    string? ServerTimeZoneId = null,
    string? HubFingerprint = null);

public static class RegistrationSchema
{
    public const int CurrentVersion = 1;
}

public static class RegistrationStates
{
    public const string Registered = "Registered";
}
