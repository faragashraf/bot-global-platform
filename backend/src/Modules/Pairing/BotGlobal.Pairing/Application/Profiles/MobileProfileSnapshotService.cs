using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Pairing.Application.Profiles;

public sealed record PublishMobileProfileSnapshotRequest(
    string ExternalSubjectId,
    string DisplayName,
    string? JobTitle,
    string? OrganizationUnit,
    long Version,
    DateTimeOffset PublishedAtUtc);

public enum MobileProfilePublishOutcome
{
    Created,
    Updated,
    Unchanged,
    StaleIgnored,
    VersionConflict,
    SubjectNotPaired
}

public sealed record PublishMobileProfileSnapshotResult(
    MobileProfilePublishOutcome Outcome,
    long? CurrentVersion);

public sealed record MobileProfileSnapshotResponse(
    string DisplayName,
    string? JobTitle,
    string? OrganizationUnit,
    long Version,
    DateTimeOffset UpdatedAtUtc);

public interface IMobileProfileSnapshotService
{
    Task<PublishMobileProfileSnapshotResult> PublishAsync(
        Guid platformClientId,
        PublishMobileProfileSnapshotRequest request,
        CancellationToken cancellationToken);

    Task<MobileProfileSnapshotResponse?> ReadAsync(
        Guid platformClientId,
        string externalSubjectId,
        CancellationToken cancellationToken);
}

internal sealed class MobileProfileSnapshotService(
    PairingDbContext dbContext,
    TimeProvider timeProvider)
    : IMobileProfileSnapshotService
{
    public async Task<PublishMobileProfileSnapshotResult> PublishAsync(
        Guid platformClientId,
        PublishMobileProfileSnapshotRequest request,
        CancellationToken cancellationToken)
    {
        if (platformClientId == Guid.Empty)
        {
            throw new ArgumentException(
                "Platform client id is required.",
                nameof(platformClientId));
        }

        ArgumentNullException.ThrowIfNull(request);

        var subject = NormalizeRequired(
            request.ExternalSubjectId,
            PairingChallenge.ExternalSubjectIdMaxLength,
            nameof(request.ExternalSubjectId));
        var displayName = NormalizeRequired(
            request.DisplayName,
            MobileProfileSnapshot.DisplayNameMaxLength,
            nameof(request.DisplayName));
        var jobTitle = NormalizeOptional(
            request.JobTitle,
            MobileProfileSnapshot.JobTitleMaxLength,
            nameof(request.JobTitle));
        var organizationUnit = NormalizeOptional(
            request.OrganizationUnit,
            MobileProfileSnapshot.OrganizationUnitMaxLength,
            nameof(request.OrganizationUnit));

        if (request.Version <= 0)
        {
            throw new ArgumentOutOfRangeException(
                nameof(request.Version),
                "Profile snapshot version must be positive.");
        }

        if (request.PublishedAtUtc == default)
        {
            throw new ArgumentException(
                "Profile snapshot publication time is required.",
                nameof(request.PublishedAtUtc));
        }

        var hasActivePairing = await dbContext.Devices
            .AsNoTracking()
            .AnyAsync(
                device =>
                    device.PlatformClientId == platformClientId
                    && device.ExternalSubjectId == subject
                    && device.RevokedAtUtc == null,
                cancellationToken);

        if (!hasActivePairing)
        {
            return new(
                MobileProfilePublishOutcome.SubjectNotPaired,
                null);
        }

        var snapshot = await dbContext.ProfileSnapshots
            .SingleOrDefaultAsync(
                candidate =>
                    candidate.PlatformClientId == platformClientId
                    && candidate.ExternalSubjectId == subject,
                cancellationToken);
        var publishedAtUtc = request.PublishedAtUtc.ToUniversalTime();

        if (snapshot is not null)
        {
            if (request.Version < snapshot.Version)
            {
                return new(
                    MobileProfilePublishOutcome.StaleIgnored,
                    snapshot.Version);
            }

            if (request.Version == snapshot.Version)
            {
                return new(
                    snapshot.HasSameContent(
                        displayName,
                        jobTitle,
                        organizationUnit,
                        publishedAtUtc)
                        ? MobileProfilePublishOutcome.Unchanged
                        : MobileProfilePublishOutcome.VersionConflict,
                    snapshot.Version);
            }

            snapshot.Apply(
                displayName,
                jobTitle,
                organizationUnit,
                request.Version,
                publishedAtUtc,
                timeProvider.GetUtcNow());
            await dbContext.SaveChangesAsync(cancellationToken);
            return new(
                MobileProfilePublishOutcome.Updated,
                snapshot.Version);
        }

        snapshot = new MobileProfileSnapshot(
            Guid.NewGuid(),
            platformClientId,
            subject,
            displayName,
            jobTitle,
            organizationUnit,
            request.Version,
            publishedAtUtc,
            timeProvider.GetUtcNow());
        dbContext.ProfileSnapshots.Add(snapshot);
        await dbContext.SaveChangesAsync(cancellationToken);

        return new(
            MobileProfilePublishOutcome.Created,
            snapshot.Version);
    }

    public Task<MobileProfileSnapshotResponse?> ReadAsync(
        Guid platformClientId,
        string externalSubjectId,
        CancellationToken cancellationToken)
    {
        if (platformClientId == Guid.Empty
            || string.IsNullOrWhiteSpace(externalSubjectId))
        {
            return Task.FromResult<MobileProfileSnapshotResponse?>(null);
        }

        var subject = externalSubjectId.Trim();
        return dbContext.ProfileSnapshots
            .AsNoTracking()
            .Where(snapshot =>
                snapshot.PlatformClientId == platformClientId
                && snapshot.ExternalSubjectId == subject)
            .Select(snapshot => new MobileProfileSnapshotResponse(
                snapshot.DisplayName,
                snapshot.JobTitle,
                snapshot.OrganizationUnit,
                snapshot.Version,
                snapshot.PublishedAtUtc))
            .SingleOrDefaultAsync(cancellationToken);
    }

    private static string NormalizeRequired(
        string value,
        int maxLength,
        string parameterName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value, parameterName);
        return Normalize(value, maxLength, parameterName);
    }

    private static string? NormalizeOptional(
        string? value,
        int maxLength,
        string parameterName)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return null;
        }

        return Normalize(value, maxLength, parameterName);
    }

    private static string Normalize(
        string value,
        int maxLength,
        string parameterName)
    {
        var normalized = value.Trim();

        if (normalized.Length > maxLength)
        {
            throw new ArgumentException(
                $"{parameterName} exceeds the maximum length of {maxLength}.",
                parameterName);
        }

        if (normalized.Any(character =>
                char.IsControl(character)
                || character is '<' or '>'))
        {
            throw new ArgumentException(
                $"{parameterName} contains unsupported content.",
                parameterName);
        }

        return normalized;
    }
}
