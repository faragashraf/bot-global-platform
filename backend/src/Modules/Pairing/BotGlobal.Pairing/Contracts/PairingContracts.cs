namespace BotGlobal.Pairing.Contracts;

public sealed record CreatePairingChallengeRequest(
    string? CorrelationReference,
    string ExternalSubjectId);

public sealed record CreatePairingChallengeResponse(
    Guid ChallengeId,
    string QrPayload,
    DateTimeOffset ExpiresAtUtc);

public sealed record PairingChallengeStatusResponse(
    Guid ChallengeId,
    string Status,
    DateTimeOffset ExpiresAtUtc,
    DateTimeOffset? CompletedAtUtc,
    string? CorrelationReference,
    ClaimedMobileDeviceResponse? Device);

public sealed record ClaimPairingChallengeRequest(
    string PairingToken,
    ClaimPairingDeviceRequest Device);

public sealed record ClaimPairingDeviceRequest(
    string Platform,
    string InstallationId,
    string? DeviceName,
    string? AppVersion);

public sealed record MobileDevicePairingCredentialResponse(
    Guid DeviceId,
    string Credential);

public sealed record ClaimPairingChallengeResponse(
    Guid ChallengeId,
    string Status,
    DateTimeOffset ExpiresAtUtc,
    DateTimeOffset CompletedAtUtc,
    MobileDevicePairingCredentialResponse Device);

public sealed record ClaimedMobileDeviceResponse(
    string Platform,
    string InstallationId,
    string? DeviceName,
    string? AppVersion);

public static class PairingChallengeStatusNames
{
    public const string Pending = "pending";
    public const string Completed = "completed";
    public const string Expired = "expired";
}
