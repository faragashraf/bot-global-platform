using System.Security.Cryptography;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application;

public sealed class PairingChallengeService(
    PairingDbContext dbContext,
    IPairingTokenService tokenService,
    IMobileDeviceCredentialService deviceCredentialService,
    MobileDeviceAuditRecorder auditRecorder,
    TimeProvider timeProvider)
    : IPairingChallengeService
{
    public static readonly TimeSpan DefaultChallengeLifetime =
        TimeSpan.FromMinutes(3);

    public async Task<CreatePairingChallengeResponse> CreateAsync(
        Guid platformClientId,
        CreatePairingChallengeRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);

        var generated = tokenService.Generate();
        var utcNow = timeProvider.GetUtcNow();

        try
        {
            var challenge =
                PairingChallenge.Create(
                    platformClientId,
                    generated.TokenHash,
                    request.CorrelationReference,
                    request.ExternalSubjectId,
                    utcNow,
                    DefaultChallengeLifetime);

            dbContext.Challenges.Add(challenge);
            await dbContext.SaveChangesAsync(cancellationToken);

            return new CreatePairingChallengeResponse(
                challenge.Id,
                generated.PlainTextToken,
                challenge.ExpiresAtUtc);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(generated.TokenHash);
        }
    }

    public async Task<PairingChallengeStatusResponse?> GetStatusAsync(
        Guid platformClientId,
        Guid challengeId,
        CancellationToken cancellationToken = default)
    {
        if (platformClientId == Guid.Empty || challengeId == Guid.Empty)
        {
            return null;
        }

        var challenge =
            await dbContext.Challenges
                .AsNoTracking()
                .SingleOrDefaultAsync(
                    item =>
                        item.Id == challengeId
                        && item.PlatformClientId == platformClientId,
                    cancellationToken);

        return challenge is null
            ? null
            : MapStatus(challenge, timeProvider.GetUtcNow());
    }

    public async Task<ClaimPairingChallengeResult> ClaimAsync(
        ClaimPairingChallengeRequest request,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(request);
        var normalizedToken = NormalizePairingToken(request.PairingToken);
        var device = MobileDeviceInputNormalizer.Normalize(request.Device);

        var tokenHash = tokenService.Hash(normalizedToken);

        try
        {
            var challenge =
                await dbContext.Challenges
                    .SingleOrDefaultAsync(
                        item => item.TokenHash.SequenceEqual(tokenHash),
                        cancellationToken);

            if (challenge is null
                || challenge.Status != PairingChallengeStatus.Pending
                || challenge.IsExpired(timeProvider.GetUtcNow()))
            {
                return InvalidExpiredOrUsed();
            }

            try
            {
                var completedAtUtc = timeProvider.GetUtcNow();
                challenge.Complete(device, completedAtUtc);

                var issuedCredential =
                    deviceCredentialService.Generate();

                var mobileDevice =
                    await dbContext.Devices
                        .SingleOrDefaultAsync(
                            item =>
                                item.PlatformClientId
                                    == challenge.PlatformClientId
                                && item.InstallationId
                                    == device.InstallationId,
                            cancellationToken);

                if (mobileDevice is null)
                {
                    mobileDevice =
                        new MobileDevice(
                            Guid.NewGuid(),
                            challenge.PlatformClientId,
                            challenge.ExternalSubjectId
                                ?? throw new InvalidOperationException(
                                    "New pairing challenge has no external subject identity."),
                            device.InstallationId,
                            device.Platform,
                            device.DeviceName,
                            device.AppVersion,
                            issuedCredential.Hash,
                            completedAtUtc);

                    dbContext.Devices.Add(mobileDevice);

                    auditRecorder.Record(
                        mobileDevice.Id,
                        challenge.PlatformClientId,
                        MobileDeviceAuditKinds.Paired,
                        MobileDeviceAuditActorTypes.Device,
                        null,
                        $"platform={device.Platform}; installation={device.InstallationId}",
                        completedAtUtc);
                }
                else
                {
                    mobileDevice.RotateCredential(
                        issuedCredential.Hash,
                        challenge.ExternalSubjectId
                            ?? throw new InvalidOperationException(
                                "Pairing challenge has no external subject identity."),
                        device.Platform,
                        device.DeviceName,
                        device.AppVersion,
                        completedAtUtc);

                    auditRecorder.Record(
                        mobileDevice.Id,
                        challenge.PlatformClientId,
                        MobileDeviceAuditKinds.RePaired,
                        MobileDeviceAuditActorTypes.Device,
                        null,
                        $"platform={device.Platform}; installation={device.InstallationId}",
                        completedAtUtc);
                }

                await dbContext.SaveChangesAsync(cancellationToken);

                return new ClaimPairingChallengeResult(
                    ClaimPairingChallengeOutcome.Completed,
                    new ClaimPairingChallengeResponse(
                        challenge.Id,
                        PairingChallengeStatusNames.Completed,
                        challenge.ExpiresAtUtc,
                        completedAtUtc,
                        new MobileDevicePairingCredentialResponse(
                            mobileDevice.Id,
                            issuedCredential.PlainText)));
            }
            catch (DbUpdateConcurrencyException)
            {
                return InvalidExpiredOrUsed();
            }
            catch (InvalidOperationException)
            {
                return InvalidExpiredOrUsed();
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(tokenHash);
        }
    }

    private static PairingChallengeStatusResponse MapStatus(
        PairingChallenge challenge,
        DateTimeOffset utcNow)
    {
        var status =
            challenge.Status == PairingChallengeStatus.Completed
                ? PairingChallengeStatusNames.Completed
                : challenge.IsExpired(utcNow)
                    ? PairingChallengeStatusNames.Expired
                    : PairingChallengeStatusNames.Pending;

        var device =
            challenge.Status == PairingChallengeStatus.Completed
                ? new ClaimedMobileDeviceResponse(
                    challenge.MobilePlatform ?? string.Empty,
                    challenge.MobileInstallationId ?? string.Empty,
                    challenge.MobileDeviceName,
                    challenge.MobileAppVersion)
                : null;

        return new PairingChallengeStatusResponse(
            challenge.Id,
            status,
            challenge.ExpiresAtUtc,
            challenge.CompletedAtUtc,
            challenge.CorrelationReference,
            device);
    }

    private string NormalizePairingToken(string pairingToken)
    {
        if (!tokenService.HasSupportedTokenFormat(pairingToken))
        {
            throw new ArgumentException(
                "Pairing token is malformed.",
                nameof(pairingToken));
        }

        return pairingToken.Trim();
    }

    private static ClaimPairingChallengeResult InvalidExpiredOrUsed()
        => new(
            ClaimPairingChallengeOutcome.InvalidExpiredOrUsed,
            null);

}
