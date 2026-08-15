using BotGlobal.Pairing.Application;
using BotGlobal.Pairing.Contracts;
using BotGlobal.Pairing.Domain;
using BotGlobal.Pairing.Infrastructure.Persistence;
using BotGlobal.Pairing.Security;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingChallengeServiceTests
{
    private static readonly DateTimeOffset Now =
        new(2026, 8, 14, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task Create_stores_hash_only_and_returns_safe_opaque_qr_payload()
    {
        var fixture = CreateFixture();
        var ownerId = Guid.NewGuid();

        var response =
            await fixture.Service.CreateAsync(
                ownerId,
                new CreatePairingChallengeRequest(
                    "connect-request-123"));

        using var verification = fixture.CreateContext();
        var stored = await verification.Challenges.SingleAsync();

        Assert.Equal(ownerId, stored.PlatformClientId);
        Assert.Equal("connect-request-123", stored.CorrelationReference);
        Assert.Equal(32, stored.TokenHash.Length);
        Assert.NotEqual(response.QrPayload, Convert.ToHexString(stored.TokenHash));
        Assert.Equal(Now.AddMinutes(3), response.ExpiresAtUtc);
        Assert.Equal(PairingChallengeStatus.Pending, stored.Status);
        Assert.Null(stored.CompletedAtUtc);

        Assert.DoesNotContain("connect-request-123", response.QrPayload, StringComparison.Ordinal);
        Assert.DoesNotContain("client", response.QrPayload, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("@", response.QrPayload, StringComparison.Ordinal);
        Assert.DoesNotContain(".", response.QrPayload, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Status_is_visible_only_to_owning_platform_client()
    {
        var fixture = CreateFixture();
        var ownerId = Guid.NewGuid();
        var otherClientId = Guid.NewGuid();

        var created =
            await fixture.Service.CreateAsync(
                ownerId,
                new CreatePairingChallengeRequest("reference-1"));

        var ownerStatus =
            await fixture.Service.GetStatusAsync(
                ownerId,
                created.ChallengeId);

        var otherStatus =
            await fixture.Service.GetStatusAsync(
                otherClientId,
                created.ChallengeId);

        Assert.NotNull(ownerStatus);
        Assert.Equal(PairingChallengeStatusNames.Pending, ownerStatus!.Status);
        Assert.Equal("reference-1", ownerStatus.CorrelationReference);
        Assert.Null(otherStatus);
    }

    [Fact]
    public async Task Status_returns_expired_when_pending_challenge_passes_expiry()
    {
        var fixture = CreateFixture();
        var created =
            await fixture.Service.CreateAsync(
                Guid.NewGuid(),
                new CreatePairingChallengeRequest(null));

        fixture.Clock.Advance(TimeSpan.FromMinutes(4));

        var status =
            await fixture.Service.GetStatusAsync(
                fixture.OwnerFallback,
                created.ChallengeId);

        Assert.Null(status);

        using var context = fixture.CreateContext();
        var stored = await context.Challenges.SingleAsync();

        var ownStatus =
            await fixture.Service.GetStatusAsync(
                stored.PlatformClientId,
                created.ChallengeId);

        Assert.Equal(PairingChallengeStatusNames.Expired, ownStatus!.Status);
    }

    [Fact]
    public async Task Valid_mobile_claim_completes_challenge_and_persists_bounded_device_metadata()
    {
        var fixture = CreateFixture();
        var ownerId = Guid.NewGuid();

        var created =
            await fixture.Service.CreateAsync(
                ownerId,
                new CreatePairingChallengeRequest("request-42"));

        var claim =
            await fixture.Service.ClaimAsync(
                ValidClaim(created.QrPayload));

        Assert.Equal(ClaimPairingChallengeOutcome.Completed, claim.Outcome);
        Assert.NotNull(claim.Response);
        Assert.Equal(created.ChallengeId, claim.Response!.ChallengeId);
        Assert.Equal(PairingChallengeStatusNames.Completed, claim.Response.Status);

        var status =
            await fixture.Service.GetStatusAsync(
                ownerId,
                created.ChallengeId);

        Assert.Equal(PairingChallengeStatusNames.Completed, status!.Status);
        Assert.Equal("android", status.Device!.Platform);
        Assert.Equal("install-123", status.Device.InstallationId);
        Assert.Equal("Pixel 8", status.Device.DeviceName);
        Assert.Equal("1.2.3", status.Device.AppVersion);
        Assert.Equal("request-42", status.CorrelationReference);
    }

    [Fact]
    public async Task Invalid_expired_and_already_completed_tokens_fail_safely()
    {
        var invalidFixture = CreateFixture();
        var invalidClaim =
            await invalidFixture.Service.ClaimAsync(
                ValidClaim("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));

        Assert.Equal(
            ClaimPairingChallengeOutcome.InvalidExpiredOrUsed,
            invalidClaim.Outcome);

        var expiredFixture = CreateFixture();
        var expired =
            await expiredFixture.Service.CreateAsync(
                Guid.NewGuid(),
                new CreatePairingChallengeRequest(null));

        expiredFixture.Clock.Advance(TimeSpan.FromMinutes(4));

        var expiredClaim =
            await expiredFixture.Service.ClaimAsync(
                ValidClaim(expired.QrPayload));

        Assert.Equal(
            ClaimPairingChallengeOutcome.InvalidExpiredOrUsed,
            expiredClaim.Outcome);

        var completedFixture = CreateFixture();
        var completed =
            await completedFixture.Service.CreateAsync(
                Guid.NewGuid(),
                new CreatePairingChallengeRequest(null));

        var first =
            await completedFixture.Service.ClaimAsync(
                ValidClaim(completed.QrPayload));

        var replay =
            await completedFixture.Service.ClaimAsync(
                ValidClaim(completed.QrPayload));

        Assert.Equal(ClaimPairingChallengeOutcome.Completed, first.Outcome);
        Assert.Equal(
            ClaimPairingChallengeOutcome.InvalidExpiredOrUsed,
            replay.Outcome);
    }

    [Fact]
    public async Task Malformed_mobile_claim_fields_are_rejected_before_persistence()
    {
        var fixture = CreateFixture();

        await Assert.ThrowsAsync<ArgumentException>(
            () => fixture.Service.ClaimAsync(
                new ClaimPairingChallengeRequest(
                    "not a supported token",
                    new ClaimPairingDeviceRequest(
                        "android",
                        "install-123",
                        null,
                        null))));

        await Assert.ThrowsAsync<ArgumentException>(
            () => fixture.Service.ClaimAsync(
                new ClaimPairingChallengeRequest(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    new ClaimPairingDeviceRequest(
                        "android",
                        new string('x', PairingChallenge.MobileInstallationIdMaxLength + 1),
                        null,
                        null))));

        await Assert.ThrowsAsync<ArgumentException>(
            () => fixture.Service.ClaimAsync(
                new ClaimPairingChallengeRequest(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    new ClaimPairingDeviceRequest(
                        "ios",
                        "install-123",
                        null,
                        null))));
    }

    [Fact]
    public async Task Concurrent_mobile_claims_have_exactly_one_winner()
    {
        var fixture = CreateFixture();
        var created =
            await fixture.Service.CreateAsync(
                Guid.NewGuid(),
                new CreatePairingChallengeRequest(null));

        var tasks =
            Enumerable
                .Range(0, 32)
                .Select(async index =>
                {
                    await using var context = fixture.CreateContext();
                    var service =
                        new PairingChallengeService(
                context,
                fixture.TokenService,
                new MobileDeviceCredentialService(),
                fixture.Clock);

                    return await service.ClaimAsync(
                        new ClaimPairingChallengeRequest(
                            created.QrPayload,
                            new ClaimPairingDeviceRequest(
                                "android",
                                $"install-{index}",
                                null,
                                "1.0.0")));
                })
                .ToArray();

        var results = await Task.WhenAll(tasks);

        Assert.Equal(
            1,
            results.Count(
                result => result.Outcome == ClaimPairingChallengeOutcome.Completed));

        Assert.Equal(
            31,
            results.Count(
                result => result.Outcome
                          == ClaimPairingChallengeOutcome.InvalidExpiredOrUsed));
    }

    private static ClaimPairingChallengeRequest ValidClaim(
        string pairingToken)
        => new(
            pairingToken,
            new ClaimPairingDeviceRequest(
                "android",
                "install-123",
                "Pixel 8",
                "1.2.3"));

    private static Fixture CreateFixture()
    {
        var databaseName = $"PairingTests-{Guid.NewGuid():N}";
        var databaseRoot = new InMemoryDatabaseRoot();
        var options =
            new DbContextOptionsBuilder<PairingDbContext>()
                .UseInMemoryDatabase(databaseName, databaseRoot)
                .Options;

        var context = new PairingDbContext(options);
        var clock = new ManualTimeProvider(Now);
        var tokenService = new PairingTokenService();

        return new Fixture(
            options,
            context,
            tokenService,
            clock);
    }

    private sealed record Fixture(
        DbContextOptions<PairingDbContext> Options,
        PairingDbContext InitialContext,
        PairingTokenService TokenService,
        ManualTimeProvider Clock)
    {
        public Guid OwnerFallback { get; } = Guid.NewGuid();

        public PairingChallengeService Service { get; } =
            new(
                InitialContext,
                TokenService,
                new MobileDeviceCredentialService(),
                Clock);

        public PairingDbContext CreateContext()
            => new(Options);
    }

    private sealed class ManualTimeProvider(
        DateTimeOffset utcNow)
        : TimeProvider
    {
        private DateTimeOffset _utcNow = utcNow;

        public override DateTimeOffset GetUtcNow()
            => _utcNow;

        public void Advance(TimeSpan timeSpan)
            => _utcNow = _utcNow.Add(timeSpan);
    }
}
