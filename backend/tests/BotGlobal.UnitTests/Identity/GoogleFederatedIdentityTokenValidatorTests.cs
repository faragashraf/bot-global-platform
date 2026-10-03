using BotGlobal.Contracts.Mobile;
using BotGlobal.Identity.Application;
using BotGlobal.Identity.Infrastructure;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Identity;

public sealed class GoogleFederatedIdentityTokenValidatorTests
{
    [Fact]
    public async Task RuntimeVerifier_RejectsMalformedToken()
    {
        var verifier = new GoogleIdTokenVerifier();

        var result = await verifier.VerifyAsync(
            "malformed-token",
            "server-client.apps.googleusercontent.com",
            CancellationToken.None);

        Assert.Null(result.Claims);
        Assert.Equal("invalid_google_token", result.Error);
    }

    [Fact]
    public async Task MissingServerAudience_IsRejectedBeforeTokenVerification()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = CreateValidator(string.Empty, verifier);

        var result = await validator.ValidateAsync(BotGlobalApplications.FamilyGames, "google", "token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("google_configuration_missing", result.Error);
        Assert.Equal(0, verifier.Calls);
    }

    [Theory]
    [InlineData("wrong_audience")]
    [InlineData("expired_token")]
    [InlineData("wrong_issuer")]
    [InlineData("invalid_signature")]
    public async Task CryptographicallyRejectedGoogleToken_NeverProducesIdentity(string reason)
    {
        var validator = CreateValidator(
            "server-client.apps.googleusercontent.com",
            new FakeVerifier(GoogleTokenVerificationResult.Failure(reason)));

        var result = await validator.ValidateAsync(BotGlobalApplications.Nqrb, "google", "untrusted-token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal(reason, result.Error);
    }

    [Fact]
    public async Task MissingProviderSubject_IsRejected()
    {
        var validator = CreateValidator(
            "server-client.apps.googleusercontent.com",
            new FakeVerifier(new GoogleTokenClaims("", "person@example.test", true, "Person")));

        var result = await validator.ValidateAsync(BotGlobalApplications.Nqrb, "google", "token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("google_identity_incomplete", result.Error);
    }

    [Fact]
    public async Task ValidGoogleIdentity_UsesProviderSubjectAndConfiguredAudience()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = CreateValidator("server-client.apps.googleusercontent.com", verifier);

        var result = await validator.ValidateAsync(BotGlobalApplications.Nqrb, "google", "transient-token", CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal("google-subject-123", result.Identity!.ProviderSubject);
        Assert.Equal(FederatedIdentityProviders.Google, result.Identity.Provider);
        Assert.Equal("server-client.apps.googleusercontent.com", verifier.Audience);
        Assert.NotEqual(result.Identity.Email, result.Identity.ProviderSubject);
    }

    [Fact]
    public async Task NqrbWebValidator_UsesOnlyTheExplicitWebAudience()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = new NqrbWebGoogleIdentityValidator(
            Options.Create(new GoogleFederatedIdentityOptions
            {
                ServerClientId = "android-server.apps.googleusercontent.com",
                NqrbWebClientId = "nqrb-web.apps.googleusercontent.com"
            }),
            verifier);

        var result = await validator.ValidateAsync("transient-token", CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal("nqrb-web.apps.googleusercontent.com", verifier.Audience);
        Assert.NotEqual("android-server.apps.googleusercontent.com", verifier.Audience);
    }

    [Fact]
    public async Task NqrbWebValidator_RejectsMissingWebAudienceWithoutFallingBackToMobileAudience()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = new NqrbWebGoogleIdentityValidator(
            Options.Create(new GoogleFederatedIdentityOptions
            {
                ServerClientId = "android-server.apps.googleusercontent.com"
            }),
            verifier);

        var result = await validator.ValidateAsync("transient-token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("google_web_configuration_missing", result.Error);
        Assert.Equal(0, verifier.Calls);
    }

    [Fact]
    public async Task FamilyGamesRequiresExplicitApplicationAudience()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = CreateValidator("legacy-nqrb.apps.googleusercontent.com", verifier);

        var result = await validator.ValidateAsync(
            BotGlobalApplications.FamilyGames,
            "google",
            "token",
            CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("google_configuration_missing", result.Error);
        Assert.Equal(0, verifier.Calls);
    }

    [Fact]
    public async Task ApplicationScopedAudiencePreventsCrossAppTokenAcceptance()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = new GoogleFederatedIdentityTokenValidator(
            Options.Create(new GoogleFederatedIdentityOptions
            {
                ServerClientId = "legacy-nqrb.apps.googleusercontent.com",
                ServerClientIds = new Dictionary<string, string>
                {
                    [BotGlobalApplications.Nqrb] = "nqrb-android.apps.googleusercontent.com",
                    [BotGlobalApplications.FamilyGames] = "family-games-android.apps.googleusercontent.com"
                }
            }),
            verifier);

        await validator.ValidateAsync(
            BotGlobalApplications.FamilyGames,
            "google",
            "token",
            CancellationToken.None);

        Assert.Equal("family-games-android.apps.googleusercontent.com", verifier.Audience);
        Assert.NotEqual("nqrb-android.apps.googleusercontent.com", verifier.Audience);
    }

    [Theory]
    [InlineData("family-games", "nqrb-token")]
    [InlineData("nqrb", "family-games-token")]
    public async Task TokenForAnotherConfiguredApplicationAudienceIsRejected(
        string applicationKey,
        string token)
    {
        var validator = new GoogleFederatedIdentityTokenValidator(
            Options.Create(new GoogleFederatedIdentityOptions
            {
                ServerClientIds = new Dictionary<string, string>
                {
                    [BotGlobalApplications.Nqrb] = "nqrb-android.apps.googleusercontent.com",
                    [BotGlobalApplications.FamilyGames] = "family-games-android.apps.googleusercontent.com"
                }
            }),
            new AudienceCheckingVerifier(new Dictionary<string, string>
            {
                ["nqrb-token"] = "nqrb-android.apps.googleusercontent.com",
                ["family-games-token"] = "family-games-android.apps.googleusercontent.com"
            }));

        var result = await validator.ValidateAsync(
            applicationKey,
            "google",
            token,
            CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("wrong_audience", result.Error);
    }

    private static GoogleFederatedIdentityTokenValidator CreateValidator(
        string clientId,
        IGoogleIdTokenVerifier verifier) =>
        new(
            Options.Create(new GoogleFederatedIdentityOptions { ServerClientId = clientId }),
            verifier);

    private static GoogleTokenClaims SuccessfulClaims() =>
        new("google-subject-123", "person@example.test", true, "Person");

    private sealed class FakeVerifier : IGoogleIdTokenVerifier
    {
        private readonly GoogleTokenVerificationResult result;

        public FakeVerifier(GoogleTokenClaims claims) : this(GoogleTokenVerificationResult.Success(claims)) { }
        public FakeVerifier(GoogleTokenVerificationResult result) => this.result = result;

        public int Calls { get; private set; }
        public string? Audience { get; private set; }

        public Task<GoogleTokenVerificationResult> VerifyAsync(
            string idToken,
            string expectedAudience,
            CancellationToken cancellationToken)
        {
            Calls++;
            Audience = expectedAudience;
            return Task.FromResult(result);
        }
    }

    private sealed class AudienceCheckingVerifier(IReadOnlyDictionary<string, string> tokenAudiences) : IGoogleIdTokenVerifier
    {
        public Task<GoogleTokenVerificationResult> VerifyAsync(
            string idToken,
            string expectedAudience,
            CancellationToken cancellationToken)
        {
            return Task.FromResult(
                tokenAudiences.TryGetValue(idToken, out var actualAudience) &&
                string.Equals(actualAudience, expectedAudience, StringComparison.Ordinal)
                    ? GoogleTokenVerificationResult.Success(SuccessfulClaims())
                    : GoogleTokenVerificationResult.Failure("wrong_audience"));
        }
    }
}
