using System.Security.Cryptography;
using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Domain;
using BotGlobal.Identity.Infrastructure.Persistence;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.EntityFrameworkCore;

namespace BotGlobal.Identity.Application;

public sealed record IssuedMobileApplicationSession(
    MobileApplicationSession Session,
    string AccessToken,
    string RefreshToken);

public interface IMobileApplicationTokenService
{
    Task<IssuedMobileApplicationSession> IssueAsync(
        ApplicationMembership membership,
        CancellationToken cancellationToken);

    Task<IssuedMobileApplicationSession?> RefreshAsync(
        string refreshToken,
        string applicationKey,
        CancellationToken cancellationToken);

    Task RevokeAccessTokenAsync(string accessToken, CancellationToken cancellationToken);
}

internal sealed class MobileApplicationTokenService(
    IdentityDbContext dbContext,
    TimeProvider timeProvider) : IMobileApplicationTokenService
{
    private static readonly TimeSpan AccessLifetime = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan RefreshLifetime = TimeSpan.FromDays(30);

    public async Task<IssuedMobileApplicationSession> IssueAsync(
        ApplicationMembership membership,
        CancellationToken cancellationToken)
    {
        var now = timeProvider.GetUtcNow();
        var accessToken = GenerateToken();
        var refreshToken = GenerateToken();
        var session = new MobileApplicationSession(
            Guid.NewGuid(),
            membership.Id,
            Hash(accessToken),
            Hash(refreshToken),
            now.Add(AccessLifetime),
            now.Add(RefreshLifetime),
            now);

        dbContext.MobileApplicationSessions.Add(session);
        await dbContext.SaveChangesAsync(cancellationToken);
        return new IssuedMobileApplicationSession(session, accessToken, refreshToken);
    }

    public async Task<IssuedMobileApplicationSession?> RefreshAsync(
        string refreshToken,
        string applicationKey,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(refreshToken))
        {
            return null;
        }

        var hash = Hash(refreshToken);
        var session = await dbContext.MobileApplicationSessions
            .Include(x => x.Membership)
            .SingleOrDefaultAsync(
                x => x.RefreshTokenHash.SequenceEqual(hash),
                cancellationToken);

        var now = timeProvider.GetUtcNow();
        if (session is null ||
            !session.IsRefreshValid(now) ||
            !session.Membership.IsActive ||
            !string.Equals(session.Membership.ApplicationKey, applicationKey, StringComparison.Ordinal))
        {
            return null;
        }

        var nextAccess = GenerateToken();
        var nextRefresh = GenerateToken();
        session.Rotate(
            Hash(nextAccess),
            Hash(nextRefresh),
            now.Add(AccessLifetime),
            now.Add(RefreshLifetime));
        await dbContext.SaveChangesAsync(cancellationToken);
        return new IssuedMobileApplicationSession(session, nextAccess, nextRefresh);
    }

    public async Task RevokeAccessTokenAsync(string accessToken, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(accessToken))
        {
            return;
        }

        var hash = Hash(accessToken);
        var session = await dbContext.MobileApplicationSessions
            .SingleOrDefaultAsync(x => x.AccessTokenHash.SequenceEqual(hash), cancellationToken);

        if (session is null)
        {
            return;
        }

        session.Revoke(timeProvider.GetUtcNow());
        await dbContext.SaveChangesAsync(cancellationToken);
    }

    internal static byte[] Hash(string token) =>
        SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(token));

    private static string GenerateToken() =>
        WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
}
