using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace BotGlobal.Pairing.Security;

public sealed record GeneratedPairingToken(
    string PlainTextToken,
    byte[] TokenHash);

public interface IPairingTokenService
{
    GeneratedPairingToken Generate();
    byte[] Hash(string pairingToken);
    bool HasSupportedTokenFormat(string pairingToken);
}

public sealed partial class PairingTokenService
    : IPairingTokenService
{
    public const int TokenEntropyBytes = 32;
    public const int TokenHashBytes = 32;
    public const int MaxTokenLength = 256;

    public GeneratedPairingToken Generate()
    {
        var random = RandomNumberGenerator.GetBytes(TokenEntropyBytes);

        try
        {
            var token = Base64UrlEncode(random);

            return new GeneratedPairingToken(
                token,
                Hash(token));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(random);
        }
    }

    public byte[] Hash(string pairingToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pairingToken);
        return SHA256.HashData(Encoding.UTF8.GetBytes(pairingToken));
    }

    public bool HasSupportedTokenFormat(string pairingToken)
        => !string.IsNullOrWhiteSpace(pairingToken)
           && pairingToken.Length <= MaxTokenLength
           && PairingTokenPattern().IsMatch(pairingToken);

    private static string Base64UrlEncode(ReadOnlySpan<byte> value)
        => Convert.ToBase64String(value)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');

    [GeneratedRegex("^[A-Za-z0-9_-]+$", RegexOptions.CultureInvariant)]
    private static partial Regex PairingTokenPattern();
}
