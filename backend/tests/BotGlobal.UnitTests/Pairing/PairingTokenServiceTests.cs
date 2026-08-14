using BotGlobal.Pairing.Security;

namespace BotGlobal.UnitTests.Pairing;

public sealed class PairingTokenServiceTests
{
    [Fact]
    public void Generated_token_has_approved_entropy_and_url_safe_format()
    {
        var service = new PairingTokenService();

        var generated = service.Generate();

        Assert.Equal(PairingTokenService.TokenEntropyBytes, 32);
        Assert.Equal(32, generated.TokenHash.Length);
        Assert.Matches("^[A-Za-z0-9_-]+$", generated.PlainTextToken);
        Assert.DoesNotContain("=", generated.PlainTextToken, StringComparison.Ordinal);
        Assert.DoesNotContain("+", generated.PlainTextToken, StringComparison.Ordinal);
        Assert.DoesNotContain("/", generated.PlainTextToken, StringComparison.Ordinal);
        Assert.NotEqual(generated.PlainTextToken, Convert.ToHexString(generated.TokenHash));
    }

    [Fact]
    public void Hash_is_deterministic_and_does_not_return_raw_token_bytes()
    {
        var service = new PairingTokenService();
        const string token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

        var first = service.Hash(token);
        var second = service.Hash(token);

        Assert.Equal(first, second);
        Assert.Equal(32, first.Length);
        Assert.NotEqual(
            System.Text.Encoding.UTF8.GetBytes(token),
            first);
    }

    [Theory]
    [InlineData("")]
    [InlineData("token with spaces")]
    [InlineData("token.with.dots")]
    public void Unsupported_token_formats_are_rejected(
        string token)
    {
        var service = new PairingTokenService();

        Assert.False(service.HasSupportedTokenFormat(token));
    }
}
