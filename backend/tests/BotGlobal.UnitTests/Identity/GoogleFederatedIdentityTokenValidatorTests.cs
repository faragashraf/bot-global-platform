using BotGlobal.Identity.Application;
using BotGlobal.Identity.Infrastructure;
using Microsoft.Extensions.Options;

namespace BotGlobal.UnitTests.Identity;

public sealed class GoogleFederatedIdentityTokenValidatorTests
{
    [Fact]
    public async Task MissingServerAudience_IsRejectedBeforeTokenVerification()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = CreateValidator(string.Empty, verifier);

        var result = await validator.ValidateAsync("google", "token", CancellationToken.None);

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

        var result = await validator.ValidateAsync("google", "untrusted-token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal(reason, result.Error);
    }

    [Fact]
    public async Task MissingProviderSubject_IsRejected()
    {
        var validator = CreateValidator(
            "server-client.apps.googleusercontent.com",
            new FakeVerifier(new GoogleTokenClaims("", "person@example.test", true, "Person")));

        var result = await validator.ValidateAsync("google", "token", CancellationToken.None);

        Assert.False(result.Succeeded);
        Assert.Equal("google_identity_incomplete", result.Error);
    }

    [Fact]
    public async Task ValidGoogleIdentity_UsesProviderSubjectAndConfiguredAudience()
    {
        var verifier = new FakeVerifier(SuccessfulClaims());
        var validator = CreateValidator("server-client.apps.googleusercontent.com", verifier);

        var result = await validator.ValidateAsync("google", "transient-token", CancellationToken.None);

        Assert.True(result.Succeeded);
        Assert.Equal("google-subject-123", result.Identity!.ProviderSubject);
        Assert.Equal(FederatedIdentityProviders.Google, result.Identity.Provider);
        Assert.Equal("server-client.apps.googleusercontent.com", verifier.Audience);
        Assert.NotEqual(result.Identity.Email, result.Identity.ProviderSubject);
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
}
