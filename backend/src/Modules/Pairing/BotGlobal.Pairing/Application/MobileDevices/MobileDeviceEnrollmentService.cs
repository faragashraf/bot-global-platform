using BotGlobal.Contracts.Notifications;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.MobileDevices;

public sealed record EnrollMobileDeviceRequest(
    string InstallationId,
    string Platform,
    string? DeviceName,
    string? AppVersion);

public sealed record EnrolledMobileDeviceResponse(
    Guid DeviceId,
    string Credential);

public interface IMobileDeviceEnrollmentService
{
    Task<EnrolledMobileDeviceResponse> EnrollAsync(
        string applicationKey,
        string externalSubjectId,
        EnrollMobileDeviceRequest request,
        CancellationToken cancellationToken);
}

internal sealed class MobileDeviceEnrollmentService(
    PairingDbContext dbContext,
    IPlatformClientApplicationResolver applications,
    IMobileDeviceCredentialService credentials,
    MobileDeviceAuditRecorder auditRecorder,
    TimeProvider timeProvider)
    : IMobileDeviceEnrollmentService
{
    public async Task<EnrolledMobileDeviceResponse> EnrollAsync(
        string applicationKey,
        string externalSubjectId,
        EnrollMobileDeviceRequest request,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationKey);
        ArgumentException.ThrowIfNullOrWhiteSpace(externalSubjectId);
        ArgumentNullException.ThrowIfNull(request);

        var application = await applications.FindByClientKeyAsync(
            applicationKey,
            cancellationToken);
        if (application is null || !application.IsActive)
        {
            throw new MobileDeviceEnrollmentApplicationException();
        }

        var subject = externalSubjectId.Trim();
        if (subject.Length > PairingChallenge.ExternalSubjectIdMaxLength)
        {
            throw new ArgumentException(
                "Authenticated subject identity is malformed.",
                nameof(externalSubjectId));
        }

        var normalized = MobileDeviceInputNormalizer.Normalize(
            new ClaimPairingDeviceRequest(
                request.Platform,
                request.InstallationId,
                request.DeviceName,
                request.AppVersion));
        var issuedCredential = credentials.Generate();
        var now = timeProvider.GetUtcNow();

        var device = await dbContext.Devices.SingleOrDefaultAsync(
            item =>
                item.PlatformClientId == application.PlatformClientId
                && item.InstallationId == normalized.InstallationId,
            cancellationToken);

        if (device is null)
        {
            device = new MobileDevice(
                Guid.NewGuid(),
                application.PlatformClientId,
                subject,
                normalized.InstallationId,
                normalized.Platform,
                normalized.DeviceName,
                normalized.AppVersion,
                issuedCredential.Hash,
                now);
            dbContext.Devices.Add(device);

            auditRecorder.Record(
                device.Id,
                application.PlatformClientId,
                MobileDeviceAuditKinds.EnrolledByApplicationIdentity,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"platform={normalized.Platform}",
                now);
        }
        else
        {
            device.RotateCredential(
                issuedCredential.Hash,
                subject,
                normalized.Platform,
                normalized.DeviceName,
                normalized.AppVersion,
                now);

            auditRecorder.Record(
                device.Id,
                application.PlatformClientId,
                MobileDeviceAuditKinds.ReEnrolledByApplicationIdentity,
                MobileDeviceAuditActorTypes.Device,
                null,
                $"platform={normalized.Platform}",
                now);
        }

        await dbContext.SaveChangesAsync(cancellationToken);
        return new EnrolledMobileDeviceResponse(
            device.Id,
            issuedCredential.PlainText);
    }
}

public sealed class MobileDeviceEnrollmentApplicationException : Exception
{
}
